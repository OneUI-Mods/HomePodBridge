// SPDX-License-Identifier: Apache-2.0
#include "../../main/cpp/hal_owned_layout.h"
#include <assert.h>
#include <stdio.h>
static uint8_t fixture[0x200000];
static const uint64_t base=0x10000000,mono=0x101c0000,node=0x101e0000,owner=0x101e1000,sink=0x101e2000;
static int write_calls,write_failure;
static int write_fixture(void* ignored,uint64_t at,const void* input,size_t length) {
    (void)ignored;assert(at==sink+0xe8 && length==8);++write_calls;
    if (write_failure==2) return 0;
    if (write_failure==1) {memcpy(fixture+at-base,input,4);write_failure=0;return 0;}
    memcpy(fixture+at-base,input,length);return 1;
}
static int read_fixture(void* ignored,uint64_t at,void* output,size_t length) {
    (void)ignored;if (at<base || at+length>base+sizeof(fixture)) return 0;
    memcpy(output,fixture+at-base,length);return 1;
}
static void set64(uint64_t at,uint64_t value) {memcpy(fixture+at-base,&value,8);}
static void set32(uint64_t at,uint32_t value) {memcpy(fixture+at-base,&value,4);}
static void set8(uint64_t at,uint8_t value) {fixture[at-base]=value;}
int main(void) {
    const char* address="123456:ap:78mixp:0";
    set32(mono+0x466c,0xd503245f);set32(mono+0x4670,0xf9007401);set32(mono+0x4674,0xd65f03c0);
    set64(base+0x1b4738,node);set64(base+0x1b4740,1);
    set8(node+32,(uint8_t)(strlen(address)*2));memcpy(fixture+node+33-base,address,strlen(address)+1);
    set64(node+64,owner);set32(owner+0x28,44100);set64(owner+0x58,4);set64(owner+0x60,4096);
    set8(owner+0x68,1);set32(owner+0x6c,1);set8(owner+0x72,1);set64(owner+0x80,sink);
    set64(sink,mono+0x8018);set64(sink+0x30,4);set64(sink+0x40,4096);set64(sink+0xe8,2587);set8(sink+0xf0,1);
    hp_owned_pipe result={0};
    assert(!hp_find_owned(read_fixture,NULL,base,mono,address,&result));
    assert(result.route==owner && result.sink==sink && result.target_address==sink+0xe8 && result.target==2587);
    uint8_t original[256],expected[256];memcpy(original,fixture+sink-base,256);
    assert(hp_change_reserve(read_fixture,write_fixture,NULL,&result,0,2587,1856));assert(!write_calls);
    assert(hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,2587,960));assert(!write_calls);
    assert(!hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,2587,1856));assert(write_calls==1);
    memcpy(expected,original,256);uint64_t lower=1856;memcpy(expected+0xe8,&lower,8);
    assert(!memcmp(expected,fixture+sink-base,256)); // No neighboring pipe state changed.
    assert(!hp_find_owned(read_fixture,NULL,base,mono,address,&result));
    assert(!hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,1856,2587));
    assert(!memcmp(original,fixture+sink-base,256));
    assert(!hp_find_owned(read_fixture,NULL,base,mono,address,&result));
    write_failure=1;assert(hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,2587,1856));
    assert(!memcmp(original,fixture+sink-base,256)); // A short write is rolled back under exclusion.
    write_failure=2;assert(hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,2587,1856));
    assert(!memcmp(original,fixture+sink-base,256));write_failure=0;
    const uint64_t experiment_targets[]={928,464,800,752,768};
    for (size_t i=0;i<5;++i) {
        uint64_t target=experiment_targets[i];
        assert(!hp_find_owned(read_fixture,NULL,base,mono,address,&result));
        assert(!hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,2587,target));
        memcpy(expected,original,256);memcpy(expected+0xe8,&target,8);
        assert(!memcmp(expected,fixture+sink-base,256));
        assert(!hp_find_owned(read_fixture,NULL,base,mono,address,&result));
        int calls_before=write_calls;
        assert(hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,target,1856));
        assert(write_calls==calls_before); // Never convert an independently changed target.
        assert(!hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,target,2587));
        assert(!memcmp(original,fixture+sink-base,256));
        assert(!hp_find_owned(read_fixture,NULL,base,mono,address,&result));
        write_failure=1;
        assert(hp_change_reserve(read_fixture,write_fixture,NULL,&result,1,2587,target));
        assert(!memcmp(original,fixture+sink-base,256));
    }
    assert(hp_find_owned(read_fixture,NULL,base,mono,"another-route",&result));
    set32(base+0x1b4700,1);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set32(base+0x1b4700,0);
    set32(owner,1);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set32(owner,0);
    set32(owner+0x6c,2);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set32(owner+0x6c,1);
    set32(owner+0x28,48000);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set32(owner+0x28,44100);
    set64(sink,mono+0x8008);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set64(sink,mono+0x8018);
    set64(sink+0xe8,960);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set64(sink+0xe8,2587);
    set8(sink+0xf1,1);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set8(sink+0xf1,0);
    set64(node,node);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));set64(node,0);
    set32(mono+0x4670,0);assert(hp_find_owned(read_fixture,NULL,base,mono,address,&result));
    puts("PASS: owned field only, change/restore, rejected/short-write rollback, exclusion, route/lock/ref/format/vtable/target/shutdown/cycle/code guards");
    return 0;
}
