"""Synthetic test: fan hum, broadband noise and delayed microphone delivery."""
import base64,unittest
import numpy as np
from analyze_acoustic_probe import analyze

class AcousticAnalysisTest(unittest.TestCase):
 def fixture(self,signal=True):
  rate=48000;output=44100;size=rate*6;origin=1_000_000_000_000.;t=np.arange(size)/rate
  rng=np.random.default_rng(17)
  pcm=.15*np.sin(2*np.pi*90*t)+.08*np.sin(2*np.pi*180*t)+rng.normal(0,.015,size)
  tones=[]
  for i,(low,high) in enumerate([(1600,3900),(2100,4700),(1300,3400)]):
   send=.9+i*.6;tones.append({"lowHz":low,"highHz":high,"enqueuedAtNs":int(origin+send*1e9),"phaseCode":[0xACE1,0xB4D3,0x69A7][i]})
   length=1764;u=np.arange(length)/output
   y=np.rint(32767*.12*(.5-.5*np.cos(2*np.pi*np.arange(length)/(length-1)))*np.sin(2*np.pi*(low*u+.5*(high-low)*u*u/(length/output))))/32768
   y*=np.where((tones[-1]["phaseCode"]>>(np.arange(length)*16//length))&1,1,-1)
   template=np.interp(np.arange(1920)*output/rate,np.arange(length),y)
   at=round((send+.123)*rate)
   if signal:pcm[at:at+len(template)]+=.35*template
  anchors=[]
  for frame in range(480,rate*6,480):
   capture=origin+frame*1e9/rate
   anchors.append([frame,frame,int(capture),int(capture+47_000_000)])
  return {"microphoneRate":rate,"outputRate":output,"sampleCount":size,"toneFrames":1764,"phoneSpeakerSignal":False,
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
if __name__=='__main__':unittest.main()
