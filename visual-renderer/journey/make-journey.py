import json, os, subprocess, sys, numpy as np, soundfile as sf, sherpa_onnx
LANG, VOICE, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
D=f'/home/claude/mix/jv2/{LANG}'; os.makedirs(D,exist_ok=True); os.chdir(D); VR='.'
FPS=30; W,H=720,1280; BASE_GAP=0.5; TAIL=0.6
TXT={
 'en':dict(series='EXPLAIN IT SLOW · 001',handle='@yourstudio',slow='{n}× slow',
  sent=[["This glowing pulse is your fifty rupee chai payment.","Right now it is racing through a web of computers.","And in about two seconds, you will see a green tick."],
        ["You tap pay at a chai stall in Delhi.","Your request leaves your phone, wrapped in encryption, and heads to NPCI, the central switch in Mumbai.","NPCI does not hold your money. It only routes the message."],
        ["Here is your payment, as one small message.","It says: take fifty rupees from this account, and give it to that one. We call this a transaction request."],
        ["NPCI asks your bank: is the PIN right, and is there fifty rupees?","Your bank says yes, and sets the money aside.","Then the shop's bank adds it, and both banks confirm."],
        ["That is five steps in two seconds: your app, the switch, your bank, the shop's bank, and a confirmation.","Next time it says payment pending, ask: which step got stuck?"]],
  c1=['This pulse is your ₹50 chai payment','Racing through a web of computers',('~2 seconds','from tap to green tick')],
  tags1=[('Tum','your phone'),('NPCI','the switch'),('Your bank',None),("Shop's bank",None)],
  c2=['Tap pay',('Wrapped in encryption','your request travels to NPCI'),("NPCI only routes the message",'it never holds your money')],cities=[('Tum','Delhi'),('NPCI','Mumbai')],
  c3=['Your payment, as one message',('Transaction request','isse kehte hain'),'Encrypted: only the right bank can read it'],pk=('TXN #0412','₹50.00','Chai stall bank','account ····4821'),
  c4=['Is the PIN right? Is there ₹50?','Your bank: yes, money set aside',"Shop's bank adds it, both confirm"],tags4=[('NPCI',None),('Your bank','checks PIN & balance'),("Shop's bank",'adds the money')],
  items=[("Your app","you tap pay"),("NPCI","the switch"),("Your bank","checks PIN & balance"),("Shop's bank","adds the money"),("Confirmation","green tick")],cta=('Payment pending?','which step got stuck?')),
 'hi':dict(series='DHEERE SE SAMJHO · 001',handle='@yourstudio',slow='{n}× dheere',
  sent=[["यह चमकता हुआ पल्स तुम्हारा पचास रुपये का चाय पेमेंट है।","अभी यह कंप्यूटरों के एक जाल से भाग रहा है।","और करीब दो सेकंड में तुम्हें ग्रीन टिक दिखेगा।"],
        ["तुम दिल्ली में चाय के स्टॉल पर पे दबाते हो।","तुम्हारी रिक्वेस्ट फोन से निकलती है, एन्क्रिप्शन में लिपटी हुई, और मुंबई के सेंट्रल स्विच एनपीसीआई की तरफ जाती है।","एनपीसीआई तुम्हारा पैसा रखता नहीं। वह बस मैसेज को रूट करता है।"],
        ["यह रहा तुम्हारा पेमेंट, एक छोटे मैसेज की तरह।","इसमें लिखा है: इस अकाउंट से पचास रुपये लो, और उस अकाउंट में दे दो। इसे कहते हैं ट्रांजैक्शन रिक्वेस्ट।"],
        ["एनपीसीआई तुम्हारे बैंक से पूछता है: पिन सही है, और पचास रुपये हैं?","बैंक कहता है हाँ, और पैसा अलग रख लेता है।","फिर दुकान का बैंक उसे जोड़ता है, और दोनों बैंक कन्फर्म करते हैं।"],
        ["यह हुए पाँच स्टेप दो सेकंड में: तुम्हारा ऐप, स्विच, तुम्हारा बैंक, दुकान का बैंक, और कन्फर्मेशन।","अगली बार पेमेंट पेंडिंग दिखे, तो पूछना: कौन सा स्टेप अटका?"]],
  c1=['Yeh pulse hai tumhare ₹50 ke chai payment ka','Computers ke jaal se bhaag raha hai',('~2 second','tap se green tick tak')],
  tags1=[('Tum','tumhara phone'),('NPCI','switch'),('Tumhara bank',None),("Dukaan ka bank",None)],
  c2=['Pay dabao',('Encryption mein lipta hua','tumhari request NPCI tak'),("NPCI sirf route karta hai",'paisa nahi rakhta')],cities=[('Tum','Delhi'),('NPCI','Mumbai')],
  c3=['Ek chhota sa message',('Transaction request','isse kehte hain'),'Encrypted: sirf sahi bank padh sakta hai'],pk=('TXN #0412','₹50.00','Chai wala bank','account ····4821'),
  c4=['PIN sahi hai? ₹50 hain?','Bank: haan, paisa alag rakha','Dukaan ka bank jodta hai, dono confirm'],tags4=[('NPCI',None),('Tumhara bank','PIN aur balance check'),("Dukaan ka bank",'paisa jodta hai')],
  items=[("Tumhara app","tum pay dabate ho"),("NPCI","switch"),("Tumhara bank","PIN aur balance check"),("Dukaan ka bank","paisa jodta hai"),("Confirmation","green tick")],cta=('Payment pending dikha?','kaunsa step atka?')),
}[LANG]
KINDS=['network','globe','packet','network','recap']
SENT=TXT['sent']
# ---- narration
timing=[]; have=os.path.exists('timing.json') and len([f for f in os.listdir('audio') if f.endswith('.wav')] if os.path.isdir('audio') else [])==5
if have: timing=json.load(open('timing.json'))
else:
    kind,arg=VOICE.split(':')
    if kind=='kokoro':
        kd='/home/claude/kk/kokoro-en-v0_19'
        tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=f'{kd}/model.onnx',voices=f'{kd}/voices.bin',tokens=f'{kd}/tokens.txt',data_dir=f'{kd}/espeak-ng-data'),num_threads=4,provider='cpu')))
        say=lambda t: np.asarray(tts.generate(t,sid=int(arg),speed=0.95).samples,dtype=np.float32)
    elif kind=='khi':
        kd='/home/claude/khi/kokoro-multi-lang-v1_0'
        kw=dict(model=f'{kd}/model.onnx',voices=f'{kd}/voices.bin',tokens=f'{kd}/tokens.txt',data_dir=f'{kd}/espeak-ng-data',dict_dir=f'{kd}/dict',lexicon=f'{kd}/lexicon-us-en.txt,{kd}/lexicon-zh.txt')
        try: kc=sherpa_onnx.OfflineTtsKokoroModelConfig(lang='hi',**kw)
        except TypeError: kc=sherpa_onnx.OfflineTtsKokoroModelConfig(**kw)
        tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kc,num_threads=4,provider='cpu')))
        say=lambda t: np.asarray(tts.generate(t,sid=int(arg),speed=0.95).samples,dtype=np.float32)
    else:   # piper hindi
        import glob
        pd=f'/home/claude/hi/vits-piper-hi_IN-{arg}-medium'
        tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=glob.glob(pd+'/*.onnx')[0],tokens=pd+'/tokens.txt',data_dir=pd+'/espeak-ng-data'),num_threads=4,provider='cpu')))
        say=lambda t: np.asarray(tts.generate(t,sid=0,speed=0.95).samples,dtype=np.float32)
    SR=tts.sample_rate; os.makedirs('audio',exist_ok=True)
    for i,sents in enumerate(SENT):
        parts=[];starts=[];pos=0.0
        for j,t in enumerate(sents):
            x=say(t); starts.append(pos); parts.append(x); pos+=len(x)/SR
            gap=TAIL if j==len(sents)-1 else BASE_GAP; parts.append(np.zeros(int(gap*SR),dtype=np.float32)); pos+=gap
        sf.write(f'audio/s{i+1}.wav',np.concatenate(parts),SR); timing.append({'starts':starts,'duration':pos})
    json.dump(timing,open('timing.json','w'))
total=sum(t['duration'] for t in timing); print('narration',round(total,1),'s',flush=True)
slow=round(total/2.0)
# ---- specs
def cap(t0,t1,x,kind='cap',y=None):
    text,sub=(x if isinstance(x,tuple) else (x,None)); return dict(t0=round(t0,2),t1=round(t1,2),text=text,sub=sub,kind=kind,y=y)
cum=0.0; specs=[]
for i,k in enumerate(KINDS):
    st=timing[i]['starts']; T=timing[i]['duration']; nxt=lambda j: (st[j+1]-0.15) if j+1<len(st) else T-0.2
    clock=[2000*cum/total,2000*(cum+T)/total]; cum+=T
    sp=dict(kind=k,width=W,height=H,duration=round(T,2),fps=FPS,hud=dict(series=TXT['series'],handle=TXT['handle'],clock=clock,slow=TXT['slow'].format(n=slow)),bloom=None)
    if k=='network' and i==0:
        c=TXT['c1']; tg=TXT['tags1']
        sp['scene']=dict(seed=7,route=[[0,4],[3,3],[6,5],[9,4]],tags=[dict(name=tg[0][0],sub=tg[0][1],node=0,t0=st[0]+0.3),dict(name=tg[1][0],sub=tg[1][1],node=1,t0=st[1]),dict(name=tg[2][0],sub=tg[2][1],node=2,t0=st[2]),dict(name=tg[3][0],sub=tg[3][1],node=3,t0=st[2]+1.5)])
        sp['chips']=[cap(st[0]+0.2,nxt(0),c[0]),cap(st[1],nxt(1),c[1]),cap(st[2],T-0.2,c[2],'stat')]
    elif k=='network':
        c=TXT['c4']; tg=TXT['tags4']
        sp['scene']=dict(seed=7,route=[[0,4],[3,3],[6,5],[9,4]],tags=[dict(name=tg[0][0],sub=tg[0][1],node=1,t0=0.2),dict(name=tg[1][0],sub=tg[1][1],node=2,t0=st[0]),dict(name=tg[2][0],sub=tg[2][1],node=3,t0=st[2])])
        sp['chips']=[cap(st[0],nxt(0),c[0]),cap(st[1],nxt(1),c[1]),cap(st[2],T-0.2,c[2])]
    elif k=='globe':
        c=TXT['c2']; ct=TXT['cities']
        sp['scene']=dict(arcs=[dict(**{'from':[28.6,77.2],'to':[19.07,72.88]},t0=st[1]-0.2,t1=st[1]+3.2,loft=0.55)],cities=[dict(name=ct[0][0],sub=ct[0][1],lat=28.6,lng=77.2,t0=st[0]+0.2),dict(name=ct[1][0],sub=ct[1][1],lat=19.07,lng=72.88,t0=st[1]+2.0)],camera=[dict(t=0,lat=24,lng=77,dist=250,up=0.4),dict(t=T,lat=23,lng=76,dist=205,up=0.4)])
        sp['chips']=[cap(st[0]+0.2,nxt(0),c[0]),cap(st[1],nxt(1),c[1]),cap(st[2],T-0.2,c[2])]
    elif k=='packet':
        c=TXT['c3']; pk=TXT['pk']
        sp['scene']=dict(id=pk[0],of=pk[1],to=pk[2],sub=pk[3],lockAt=st[1]+2.0,burstAt=0.8)
        sp['chips']=[cap(st[0]+0.2,nxt(0),c[0]),cap(st[1]+0.2,st[1]+3.6,c[1],'name'),cap(st[1]+4.2,T-0.2,c[2])]
    else:
        its=TXT['items']; cta=TXT['cta']
        sp['scene']=dict(items=[dict(label=its[j][0],sub=its[j][1],t0=st[0]+0.8+j*0.95) for j in range(5)])
        sp['chips']=[cap(st[1],T-0.2,cta,'name',0.78)]
    specs.append(sp); json.dump(sp,open(f'spec{i+1}.json','w'))
# ---- frames (720x1280, 30 fps)
for i,sp in enumerate(specs):
    if os.path.exists(f'frames{i+1}/done'): continue
    r=subprocess.run(['node','journey/render-journey.mjs',f'{D}/spec{i+1}.json',f'{D}/frames{i+1}'],cwd=VR,env=dict(os.environ,CHROMIUM_PATH='/opt/pw-browsers/chromium-1194/chrome-linux/chrome'),capture_output=True,text=True,timeout=6000)
    print(r.stdout.strip()[-200:],r.stderr.strip()[-200:],flush=True); open(f'frames{i+1}/done','w').write('ok')
# ---- assemble
os.makedirs('scenes',exist_ok=True); parts=[]
for i,sp in enumerate(specs):
    subprocess.run(['ffmpeg','-nostdin','-y','-framerate',str(FPS),'-i',f'frames{i+1}/f%05d.jpg','-i',f'audio/s{i+1}.wav','-vf','format=yuv420p','-af','aresample=48000,aformat=channel_layouts=stereo,apad','-t',f'{timing[i]["duration"]:.3f}','-c:v','libx264','-preset','medium','-crf','17','-c:a','aac','-b:a','192k',f'scenes/s{i+1}.mp4'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); parts.append(f'scenes/s{i+1}.mp4')
open('concat.txt','w').write(''.join(f"file '{D}/{p}'\n" for p in parts))
subprocess.run(['ffmpeg','-nostdin','-y','-f','concat','-safe','0','-i','concat.txt','-af','loudnorm=I=-16:TP=-1.5:LRA=9','-c:v','libx264','-preset','medium','-crf','17','-pix_fmt','yuv420p','-c:a','aac','-b:a','192k',OUT],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print('ALLDONE',OUT,flush=True)
