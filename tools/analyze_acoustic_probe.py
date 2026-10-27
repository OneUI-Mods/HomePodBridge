"""Analyze an explicitly recorded acoustic probe; require ADC-frame timestamps, not read times.
Only timing/quality metrics are written. NumPy is an analysis dependency, not an app dependency.
"""
import argparse,base64,json
from pathlib import Path
import numpy as np


def analyze(record):
    if record.get("failure"): raise ValueError(record["failure"])
    if record.get("phoneSpeakerSignal") is not False: raise ValueError("Signal destination unknown")
    rate=int(record["microphoneRate"]);out_rate=int(record["outputRate"])
    x=np.frombuffer(base64.b64decode(record["pcm16le"]),dtype="<i2").astype(np.float64)/32768
    if len(x)!=record["sampleCount"] or len(x)<rate: raise ValueError("Incomplete microphone record")
    a=np.asarray(record["anchors"],dtype=np.float64)
    if a.ndim!=2 or a.shape[1]!=4 or len(a)<5: raise ValueError("Microphone timestamps unavailable")
    # Robust hardware clock fit; repeated timestamps are normal between hardware updates.
    a=a[a[:,1]>=rate*.25]
    _,indices=np.unique(a[:,1],return_index=True); a=a[np.sort(indices)]
    if len(a)<5 or np.any(np.diff(a[:,2])<=0): raise ValueError("Microphone clock is not monotonic")
    fit=a[np.linspace(0,len(a)-1,min(len(a),60)).astype(int)]
    slopes=[(v[2]-u[2])/(v[1]-u[1]) for i,u in enumerate(fit) for v in fit[i+1:] if v[1]-u[1]>rate*.25]
    if not slopes:raise ValueError("Insufficient timestamp span")
    slope=float(np.median(slopes));nominal=1e9/rate
    if abs(slope/nominal-1)>.01:raise ValueError("Microphone timestamp rate inconsistent")
    origin=float(np.median(a[:,2]-a[:,1]*slope))
    residual=np.abs(a[:,2]-(origin+a[:,1]*slope))/1e6
    delivery=(a[:,3]-(origin+a[:,0]*slope))/1e6
    if np.percentile(delivery,50)<-2:raise ValueError("Microphone timestamps map after sample delivery")
    nfft=1<<int((len(x)+int(record["toneFrames"])*rate/out_rate-1)).bit_length()
    freq=np.fft.rfftfreq(nfft,1/rate)
    # A real symmetric FFT mask is zero-phase: this analysis filter adds no timing offset.
    mask=np.zeros_like(freq);mask[(freq>=1200)&(freq<=5200)]=1
    lo=(freq>900)&(freq<1200);mask[lo]=.5-.5*np.cos(np.pi*(freq[lo]-900)/300)
    hi=(freq>5200)&(freq<5800);mask[hi]=.5+.5*np.cos(np.pi*(freq[hi]-5200)/600)
    spectrum=np.fft.rfft(x,nfft)*mask
    filtered=np.fft.irfft(spectrum,nfft)[:len(x)]
    energy=np.concatenate(([0.],np.cumsum(filtered*filtered)))
    results=[]
    for tone in record["tones"]:
        marker=int(tone["enqueuedAtNs"])
        if marker<=0:raise ValueError("Sender marker missing")
        count=int(record["toneFrames"]);t=np.arange(count)/out_rate;seconds=count/out_rate
        y=np.rint(32767*.12*(.5-.5*np.cos(2*np.pi*np.arange(count)/(count-1)))*np.sin(2*np.pi*(tone["lowHz"]*t+.5*(tone["highHz"]-tone["lowHz"])*t*t/seconds)))/32768
        phase_code=int(tone.get("phaseCode",65535));chips=(np.arange(count)*16//count).astype(np.int64);y*=np.where((phase_code>>chips)&1,1,-1)
        template=np.interp(np.arange(int(np.ceil(count*rate/out_rate)))*out_rate/rate,np.arange(count),y)
        size=len(template);valid=len(x)-size+1
        corr=np.fft.irfft(spectrum*np.conj(np.fft.rfft(template,nfft)),nfft)[:valid]
        local=energy[size:]-energy[:-size]
        score=np.abs(corr)/np.sqrt(np.maximum(local*np.dot(template,template),1e-20))
        expected=(marker-origin)/slope
        begin=max(0,int(expected-rate*.1));end=min(valid,int(expected+rate*2.0))
        if end<=begin:raise ValueError("Sender marker outside microphone window")
        segment=score[begin:end];peak=begin+int(np.argmax(segment));value=float(score[peak])
        baseline=score[int(rate*.2):min(int(rate*.8),valid)]
        median=float(np.median(baseline));mad=float(np.median(np.abs(baseline-median)))*1.4826
        threshold=max(.06,median+8*mad)
        far=segment.copy();left=max(0,peak-begin-int(rate*.01));right=min(len(far),peak-begin+int(rate*.01));far[left:right]=0
        rival=float(np.max(far));prominence=value/max(rival,1e-9)
        accepted=value>=threshold and prominence>=1.25
        arrival=origin+peak*slope
        rows=a[a[:,0]>=peak+1]
        raw_ms=float((rows[0,3]-marker)/1e6) if len(rows) else None
        results.append({"accepted":bool(accepted),"correctedDelayMs":(arrival-marker)/1e6,
            "sampleReadDelayMs":raw_ms,"correlation":value,"threshold":threshold,"peakProminence":prominence})
    good=[r["correctedDelayMs"] for r in results if r["accepted"]]
    clock_p95=float(np.percentile(residual,95))
    clipping=float(np.mean(np.abs(x)>=32760/32768)*100)
    return {"reference":"Sender PCM enqueue to microphone hardware timestamp; pre-enqueue capture excluded",
        "phoneSpeakerSignal":False,"microphoneAudioSource":record.get("source"),"microphoneReadDelayExcluded":True,"adcInternalDelayCalibrated":False,
        "microphoneClockResidualP95Ms":clock_p95,"microphoneDeliveryDelayMedianMs":float(np.median(delivery)),
        "microphoneClippedPercent":clipping,"microphoneRms":float(np.sqrt(np.mean(x*x))),"tones":results,
        "matchedTones":len(good),"timingQualityAccepted":len(good)>=2 and clock_p95<=2 and clipping<.1,
        "medianDelayMs":float(np.median(good)) if len(good)>=2 else None,
        "delaySpreadMs":float(max(good)-min(good)) if len(good)>=2 else None,
        "limitation":"Uncalibrated microphone/DSP internal delay remains; this is not total source-to-speaker latency."}


def main():
    parser=argparse.ArgumentParser();parser.add_argument("record",type=Path);parser.add_argument("--output",type=Path)
    args=parser.parse_args();result=analyze(json.loads(args.record.read_text(encoding="utf-8")))
    text=json.dumps(result,indent=2);print(text)
    if args.output:args.output.write_text(text+"\n",encoding="utf-8")

if __name__=="__main__":main()
