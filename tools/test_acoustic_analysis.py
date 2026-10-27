"""Synthetic test: fan hum, broadband noise and delayed microphone delivery."""
import base64,unittest
import numpy as np
from analyze_acoustic_probe import analyze

class AcousticAnalysisTest(unittest.TestCase):
 def fixture(self,signal=True,length=1764):
  rate=48000;output=44100;size=rate*6;origin=1_000_000_000_000.;t=np.arange(size)/rate
  rng=np.random.default_rng(17)
  pcm=.15*np.sin(2*np.pi*90*t)+.08*np.sin(2*np.pi*180*t)+rng.normal(0,.015,size)
  tones=[]
  for i,(low,high) in enumerate([(1600,3900),(2100,4700),(1300,3400)]):
   send=.9+i*.6;tones.append({"lowHz":low,"highHz":high,"enqueuedAtNs":int(origin+send*1e9),"phaseCode":[0xACE1,0xB4D3,0x69A7][i]})
   u=np.arange(length)/output
   y=np.rint(32767*.12*(.5-.5*np.cos(2*np.pi*np.arange(length)/(length-1)))*np.sin(2*np.pi*(low*u+.5*(high-low)*u*u/(length/output))))/32768
   y*=np.where((tones[-1]["phaseCode"]>>(np.arange(length)*16//length))&1,1,-1)
   template=np.interp(np.arange(round(length*rate/output))*output/rate,np.arange(length),y)
   at=round((send+.123)*rate)
   if signal:pcm[at:at+len(template)]+=.35*template
  anchors=[]
  for frame in range(480,rate*6,480):
   capture=origin+frame*1e9/rate
   anchors.append([frame,frame,int(capture),int(capture+47_000_000)])
  return {"microphoneRate":rate,"outputRate":output,"sampleCount":size,"toneFrames":length,"phoneSpeakerSignal":False,
   "tones":tones,"anchors":anchors,"pcm16le":base64.b64encode(np.rint(pcm*32768).astype('<i2').tobytes()).decode()}
 def test_fan_noise_and_delivery_latency_do_not_change_hardware_time(self):
  r=analyze(self.fixture());self.assertTrue(r['timingQualityAccepted']);self.assertEqual(r['matchedTones'],3)
  self.assertAlmostEqual(r['medianDelayMs'],123,delta=.1);self.assertAlmostEqual(r['microphoneDeliveryDelayMedianMs'],47,delta=.1)
  self.assertFalse(r['adcInternalDelayCalibrated'])
 def test_environmental_noise_alone_is_not_a_measurement(self):
  r=analyze(self.fixture(False));self.assertFalse(r['timingQualityAccepted']);self.assertIsNone(r['medianDelayMs'])
 def test_missing_microphone_timestamp_is_rejected(self):
  record=self.fixture();record['anchors']=[]
  with self.assertRaises(ValueError):analyze(record)
 def test_longer_quiet_chirps_preserve_timing_and_noise_rejection(self):
  result=analyze(self.fixture(length=3528))
  self.assertTrue(result['timingQualityAccepted']);self.assertAlmostEqual(result['medianDelayMs'],123,delta=.1)
  noise=analyze(self.fixture(False,length=3528))
  self.assertFalse(noise['timingQualityAccepted']);self.assertIsNone(noise['medianDelayMs'])
 def test_paired_reference_cancels_hidden_microphone_delay(self):
  record=self.fixture(False);rate=record['microphoneRate'];out=record['outputRate'];origin=1e12
  pcm=np.frombuffer(base64.b64decode(record['pcm16le']),dtype='<i2').astype(np.float64)/32768
  all_tones=[]
  for dest,send,delay,low,high,code in [('phone',.9,.018,1800,4600,0x17D4),('phone',1.5,.018,4300,1500,0xC2B9),('homepod',2.7,.035,1600,3900,0xACE1),('homepod',3.3,.035,2100,4700,0xB4D3),('homepod',3.9,.035,1300,3400,0x69A7)]:
   n=record['toneFrames'];u=np.arange(n)/out
   y=np.rint(32767*.12*(.5-.5*np.cos(2*np.pi*np.arange(n)/(n-1)))*np.sin(2*np.pi*(low*u+.5*(high-low)*u*u/(n/out))))/32768
   y*=np.where((code>>(np.arange(n)*16//n))&1,1,-1)
   template=np.interp(np.arange(1920)*out/rate,np.arange(n),y);at=round((send+delay)*rate);pcm[at:at+len(template)]+=.35*template
   tone={'destination':dest,'enqueuedAtNs':int(origin+send*1e9),'lowHz':low,'highHz':high,'phaseCode':code}
   if dest=='phone':tone['trackFrame']=round((send+delay+.1)*rate)
   all_tones.append(tone)
  record['tones']=all_tones
  for row in record['anchors']:row[2]+=50_000_000;row[3]+=50_000_000
  record.update({'signalMode':'paired','phoneSpeakerSignal':True,'pcm16le':base64.b64encode(np.rint(pcm*32768).astype('<i2').tobytes()).decode(),
   'phoneReference':{'routedDeviceType':2,'rate':rate,'underruns':0,'volumeRestored':True,'anchors':[[f,int(origin-.1e9+f*1e9/rate),f+480,0] for f in range(480,rate*6,480)]}})
  result=analyze(record);paired=result['pairedComparison']
  self.assertTrue(paired['calibrationAccepted']);self.assertAlmostEqual(paired['homepodMinusPhonePathMs'],17,delta=.15)
  self.assertAlmostEqual(paired['micPlusPhonePostTimestampDelayEstimateMs'],50,delta=.15)
  self.assertAlmostEqual(paired['homepodDelayUsingPhoneClockEstimateMs'],35,delta=.15)
  self.assertFalse(paired['phoneDacDownstreamDelayCalibrated'])
  record['phoneReference']['underruns']=2
  self.assertFalse(analyze(record)['pairedComparison']['calibrationAccepted'])
if __name__=='__main__':unittest.main()
