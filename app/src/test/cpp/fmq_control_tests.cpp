#include "../../main/cpp/fmq_control.h"
#include "../../main/cpp/fmq_zero_transfer.h"
#include "../../main/cpp/zero_pcm_pipe.h"
#include <cassert>
#include <cstdio>

int main() {
    int packet_pipe[2];assert(pipe2(packet_pipe,O_CLOEXEC|O_NONBLOCK)==0);
    assert(hp_write_zero_packet(packet_pipe[1],1000000,800));
    std::array<uint8_t,200> packet{};assert(read(packet_pipe[0],packet.data(),packet.size())==200);
    assert(packet[0]==0x48 && packet[1]==0x42 && packet[2]==0x50 && packet[3]==0x32);
    assert(packet[7]==176 && packet[22]==3 && packet[23]==32);
    for(size_t i=24;i<packet.size();i++) assert(packet[i]==0);
    std::array<uint8_t,176> signal{};for(size_t i=0;i<signal.size();i++)signal[i]=uint8_t(i+1);
    assert(hp_write_pcm_packet(packet_pipe[1],1000000,800,signal.data(),true,false));
    assert(read(packet_pipe[0],packet.data(),packet.size())==200 && packet[3]==0x33);
    assert(std::equal(signal.begin(),signal.end(),packet.begin()+24));
    assert(hp_write_pcm_packet(packet_pipe[1],1000000,-1,signal.data(),true,true));
    assert(read(packet_pipe[0],packet.data(),packet.size())==200);
    for(size_t i=24;i<packet.size();i++)assert(packet[i]==0);
    assert(!hp_write_pcm_packet(packet_pipe[1],1000000,-2,signal.data(),true,false));
    assert(!hp_write_zero_packet(packet_pipe[1],0,0));
    assert(!hp_write_zero_packet(packet_pipe[1],1000000,4097));
    int written=0;auto blocked_begin=std::chrono::steady_clock::now();
    while(hp_write_zero_packet(packet_pipe[1],1000000,0)) {assert(++written<1024);}
    assert(written>0 && std::chrono::steady_clock::now()-blocked_begin<std::chrono::milliseconds(200));
    assert(close(packet_pipe[0])==0 && close(packet_pipe[1])==0);
    assert(hp_realtime_frames(44100,1000000));
    assert(hp_realtime_frames(66176,1500764));
    assert(!hp_realtime_frames(41800,1507894)); // Waiting must not disguise slowed playback.
    assert(!hp_realtime_frames(48000,1000000));
    assert(!hp_realtime_frames(0,1000000) && !hp_realtime_frames(44100,0));
    int cmd_fd=memfd_create("hpb-status-command",MFD_CLOEXEC);
    int reply_fd=memfd_create("hpb-status-reply",MFD_CLOEXEC);
    assert(cmd_fd>=0 && reply_fd>=0);assert(!ftruncate(cmd_fd,4096) && !ftruncate(reply_fd,4096));
    uint64_t cmd_origin=8,reply_origin=56;
    assert(pwrite(cmd_fd,&cmd_origin,8,0)==8 && pwrite(cmd_fd,&cmd_origin,8,8)==8);
    assert(pwrite(reply_fd,&reply_origin,8,0)==8 && pwrite(reply_fd,&reply_origin,8,8)==8);
    hp_grantor cg[]={{0,0,8},{0,8,8},{0,16,8},{0,24,4}};
    hp_grantor rg[]={{0,0,8},{0,8,8},{0,16,56},{0,72,4}};
    hp_mapped_queue command,reply;
    assert(command.attach(&cmd_fd,1,cg,4,8,1));assert(reply.attach(&reply_fd,1,rg,4,56,1));
    auto peer=[&](int state) {
        hp_status_command received{};
        auto deadline=std::chrono::steady_clock::now()+std::chrono::milliseconds(100);
        while(!command.view.transfer(&received,8,false)) {
            assert(std::chrono::steady_clock::now()<deadline);
            std::this_thread::sleep_for(std::chrono::microseconds(50));
        }
        assert(received.tag==1 && received.unused==0); // Never start or write PCM.
        hp_status_reply response{};response.state=state;
        assert(reply.view.transfer(&response,56,true));
    };
    hp_status_reply result{};int64_t elapsed=0;
    std::thread worker(peer,1);
    assert(hp_query_standby(command,reply,&result,&elapsed));worker.join();
    assert(result.bytes==0 && result.state==1 && elapsed>=0);
    assert(command.view.read->load()==16 && command.view.write->load()==16);
    assert(reply.view.read->load()==112 && reply.view.write->load()==112);
    std::thread unexpected(peer,3);
    assert(!hp_query_standby(command,reply,&result,&elapsed));unexpected.join();
    for(int state:{2,3}) {
        std::thread active(peer,state);
        assert(hp_input_position(command,reply,&result));active.join();
    }
    std::thread standby(peer,1);
    assert(!hp_input_position(command,reply,&result));standby.join();
    std::thread malformed([&] {
        hp_status_command received{};
        while(!command.view.transfer(&received,8,false)) std::this_thread::yield();
        assert(received.tag==1);
        hp_status_reply response{};response.state=3;response.observable.frames=-1;
        assert(reply.view.transfer(&response,56,true));
    });
    assert(!hp_input_position(command,reply,&result));malformed.join();
    hp_status_reply dirty{};assert(reply.view.transfer(&dirty,56,true));
    uint64_t before=command.view.write->load();
    assert(!hp_query_standby(command,reply,&result,&elapsed));assert(command.view.write->load()==before);
    assert(reply.view.transfer(&dirty,56,false));
    hp_status_command pending{};assert(command.view.transfer(&pending,8,true));
    before=command.view.write->load();assert(!hp_query_standby(command,reply,&result,&elapsed));
    assert(command.view.write->load()==before);assert(command.view.transfer(&pending,8,false));
    assert(!hp_query_standby(command,reply,&result,&elapsed,std::chrono::milliseconds(1)));
    assert(command.view.transfer(&pending,8,false) && pending.tag==1);
    hp_mapped_queue no_event;
    assert(no_event.attach(&cmd_fd,1,cg,3,8,1));
    before=command.view.write->load();
    assert(!hp_dispatch_status(no_event,reply));
    assert(command.view.write->load()==before); // Refuse before enqueuing an unwakeable command.
    for(auto invalid:std::array<std::pair<uint8_t,int>,7>{{{0,0},{4,0},{7,0},{2,4},{3,0},{3,3},{3,3716}}}) {
        assert(!hp_stream_dispatch(command,reply,invalid.first,invalid.second));
        assert(command.view.write->load()==before);
    }
    std::array<hp_mapped_queue,6> unopened{};
    for(int frames:{0,1,31,1024}) assert(hp_zero_transfer(unopened,frames,false).error==1);
    assert(!::close(cmd_fd) && !::close(reply_fd));
    std::puts("FMQ standby control, origins, pending-reply and timeout guards passed");
}
