// SPDX-License-Identifier: Apache-2.0
// Exact-build layout, accepted only after SHA256 verification by HalRootProbe.
#ifndef HOMEPOD_HAL_OWNED_LAYOUT_H
#define HOMEPOD_HAL_OWNED_LAYOUT_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>

typedef int (*hp_read)(void*, uint64_t, void*, size_t);
typedef int (*hp_write)(void*, uint64_t, const void*, size_t);
typedef struct { uint64_t route, sink, target_address, target, reported_frames; } hp_owned_pipe;
static int hp_manual_reserve(uint64_t n){return n>=48 && n<=800;}
static int hp_latency_window(uint64_t n){for(int ms=1;ms<=21;ms++)if(n==(uint64_t)((44100*ms/1000+15)/16)*32)return 1;return 0;}
static uint64_t hp_pointer(uint64_t value) { return value & UINT64_C(0x00ffffffffffffff); }
static uint64_t hp_u64(const void* bytes) { uint64_t value; memcpy(&value,bytes,8); return value; }
static uint32_t hp_u32(const void* bytes) { uint32_t value; memcpy(&value,bytes,4); return value; }

static const char* hp_find_owned(hp_read read, void* ctx, uint64_t module, uint64_t mono,
                                  const char* address, hp_owned_pipe* result) {
    uint8_t header[24], entry[80], route[144], pipe[256], guard[4], setter[12];
    uint64_t todo[33], seen[16]; size_t pending=0, visited=0; int found=0;
    const uint32_t expected_setter[3]={0xd503245f,0xf9007401,0xd65f03c0};
    size_t address_len=strlen(address);
    if (!address_len || address_len>128) return "registration length";
    if (!read(ctx,mono+0x466c,setter,sizeof(setter)) || memcmp(setter,expected_setter,12)) return "setter code";
    if (!read(ctx,module+0x1b4700,guard,4) || hp_u32(guard)) return "route map locked";
    if (!read(ctx,module+0x1b4730,header,24)) return "route map unavailable";
    uint64_t count=hp_u64(header+16);
    if (!count || count>16) return "route count";
    todo[pending++]=hp_pointer(hp_u64(header+8));
    while (pending) {
        uint64_t node=todo[--pending];
        if (!node) continue;
        if (visited>=count) return "route tree count";
        for (size_t i=0;i<visited;++i) if (seen[i]==node) return "route tree cycle";
        seen[visited++]=node;
        if (!read(ctx,node,entry,80)) return "route node unavailable";
        if (pending+2>33) return "route tree depth";
        todo[pending++]=hp_pointer(hp_u64(entry)); todo[pending++]=hp_pointer(hp_u64(entry+8));
        if (hp_u32(entry+56)!=0) continue;
        int large=entry[32]&1;
        uint64_t length=large?hp_u64(entry+40):(entry[32]>>1);
        if (length>128) return "route string length";
        if (length!=address_len) continue;
        uint8_t text[129];
        uint64_t text_address=large?hp_pointer(hp_u64(entry+48)):node+33;
        if (!read(ctx,text_address,text,(size_t)length+1)) return "route string unavailable";
        if (text[length] || memcmp(text,address,address_len)) continue;
        if (found++) return "duplicate registration";
        uint64_t owner=hp_pointer(hp_u64(entry+64));
        if (!read(ctx,owner,route,144)) return "owned route unavailable";
        if (hp_u32(route)) return "owned route locked";
        if (!route[0x68] || !route[0x72] || hp_u32(route+0x6c)!=1) return "owned stream references";
        if (hp_u32(route+0x28)!=44100 || hp_u64(route+0x58)!=4 || (hp_u64(route+0x60)!=4096 && !hp_latency_window(hp_u64(route+0x60))))
            return "owned format";
        uint64_t sink=hp_pointer(hp_u64(route+0x80));
        if (!read(ctx,sink,pipe,256)) return "owned pipe unavailable";
        if (hp_pointer(hp_u64(pipe))!=mono+0x8018 || hp_u64(pipe+0x30)!=4
            || hp_u64(pipe+0x40)!=4096 || pipe[0xf0]!=1 || pipe[0xf1]) return "owned pipe identity";
        uint64_t target=hp_u64(pipe+0xe8);
        if (target!=2587 && target!=1856 && target!=928 && target!=464 && target!=800 && target!=752 && target!=768 && !hp_manual_reserve(target)) return "competing reserve change";
        if(hp_u64(route+0x60)!=4096 && !hp_manual_reserve(target))return "latency window without tiny reserve";
        result->reported_frames=hp_u64(route+0x60);
        result->route=owner; result->sink=sink; result->target_address=sink+0xe8; result->target=target;
    }
    if (visited!=count) return "incomplete route tree";
    return found==1?NULL:"owned registration absent";
}

// Caller must own the complete stopped-thread set and hold that exclusion through return.
static const char* hp_change_reserve(hp_read read,hp_write write,void* ctx,const hp_owned_pipe* owned,
                                    int exclusive,uint64_t before,uint64_t after) {
    uint8_t original[256],expected[256],observed[256];
    if (!exclusive) return "exclusive access required";
    if(owned->reported_frames!=4096)return "restore latency window first";
    if (!((before==2587 && (after==1856 || after==928 || after==464 || after==800 || after==752 || after==768 || hp_manual_reserve(after)))
          || ((before==1856 || before==928 || before==464 || before==800 || before==752 || before==768 || hp_manual_reserve(before)) && after==2587)))
        return "unsupported reserve transition";
    if (owned->target_address!=owned->sink+0xe8 || owned->target!=before) return "stale owned target";
    if (!read(ctx,owned->sink,original,256) || hp_u64(original+0xe8)!=before) return "target changed";
    memcpy(expected,original,256);memcpy(expected+0xe8,&after,8);
    int complete=write(ctx,owned->target_address,&after,8);
    if (read(ctx,owned->sink,observed,256)) {
        if (complete && !memcmp(expected,observed,256)) return NULL;
        if (!memcmp(original,observed,256)) return "reserve write not applied";
    }
    // Still under the same exclusion: roll back a short write/readback mismatch.
    if (!write(ctx,owned->target_address,&before,8) || !read(ctx,owned->sink,observed,256)
        || memcmp(original,observed,256)) return "reserve rollback failed; close owned capture";
    return "reserve verification failed; original restored";
}

// Only the private route's latency calculation budget changes; allocated pipe capacity stays 4096.
static const char* hp_change_reported_frames(hp_read read,hp_write write,void* ctx,
        const hp_owned_pipe* owned,int exclusive,uint64_t before,uint64_t after) {
    uint8_t original[144],expected[144],observed[144];
    if(!exclusive || !hp_manual_reserve(owned->target) || owned->reported_frames!=before
        || !((before==4096 && hp_latency_window(after))||(hp_latency_window(before) && after==4096)))return "unsupported latency window transition";
    if(!read(ctx,owned->route,original,144) || hp_u64(original+0x60)!=before)return "stale route metadata";
    memcpy(expected,original,144);memcpy(expected+0x60,&after,8);
    int complete=write(ctx,owned->route+0x60,&after,8);
    if(read(ctx,owned->route,observed,144)) {
        if(complete && !memcmp(expected,observed,144))return NULL;
        if(!memcmp(original,observed,144))return "latency window write not applied";
    }
    if(!write(ctx,owned->route+0x60,&before,8) || !read(ctx,owned->route,observed,144)
        || memcmp(original,observed,144))return "latency window rollback failed; close owned capture";
    return "latency window verification failed; original restored";
}
#endif
