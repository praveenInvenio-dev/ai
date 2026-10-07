import os, io, traceback, gc
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel
import torch
import soundfile as sf
from transformers import AutoModelForCausalLM, AutoTokenizer, MimiModel

REPO = os.getenv('RUMIK_MODEL', 'rumik-ai/rumik-oss-1')
DEVICE = os.getenv('RUMIK_DEVICE', 'cuda')
DTYPE = torch.bfloat16 if DEVICE.startswith('cuda') else torch.float32
DEFAULT_SPEAKER = os.getenv('RUMIK_SPEAKER', 'Ira')

app = FastAPI(title='Rumik OSS-1 TTS')
_tokenizer = None
_model = None
_mimi = None

class SpeechRequest(BaseModel):
    speaker: str = DEFAULT_SPEAKER
    input: str
    temperature: float = 0.8
    top_k: int = 30
    max_new_tokens: int = 4096
    description: str = ''

def unload_models():
    global _tokenizer, _model, _mimi
    _tokenizer = None
    _model = None
    _mimi = None
    gc.collect()
    if DEVICE.startswith('cuda') and torch.cuda.is_available():
        torch.cuda.empty_cache()
        try:
            torch.cuda.ipc_collect()
        except Exception:
            pass

def load_models():
    global _tokenizer, _model, _mimi
    if _model is not None:
        return
    if DEVICE.startswith('cuda') and not torch.cuda.is_available():
        raise RuntimeError('CUDA requested but no GPU is available')
    _tokenizer = AutoTokenizer.from_pretrained(REPO, trust_remote_code=True)
    _model = AutoModelForCausalLM.from_pretrained(
        REPO, trust_remote_code=True, dtype=DTYPE
    ).eval().to(DEVICE)
    _mimi = MimiModel.from_pretrained(REPO, subfolder='codec').eval().to(DEVICE)

@app.get('/health')
def health():
    free = total = None
    if DEVICE.startswith('cuda') and torch.cuda.is_available():
        try:
            free, total = torch.cuda.mem_get_info()
            free = round(free / (1024 ** 3), 2)
            total = round(total / (1024 ** 3), 2)
        except Exception:
            pass
    return {'ok': True, 'loaded': _model is not None, 'model': REPO, 'cuda_free_gb': free, 'cuda_total_gb': total}

@app.post('/unload')
def unload():
    unload_models()
    return health() | {'unloaded': True}

@app.post('/v1/audio/speech')
def speech(req: SpeechRequest):
    try:
        load_models()
        speaker = req.speaker if req.speaker in {'Ira','Aisha','Siya','Zoya'} else DEFAULT_SPEAKER
        description = (req.description or '').strip()
        if description:
            prompt = f'<text>{speaker}: <description=\"{description}\"> {req.input}<audio>'
        else:
            prompt = f'<text>{speaker}: {req.input}<audio>'
        inputs = _tokenizer(prompt, return_tensors='pt').to(_model.device)
        with torch.inference_mode():
            ids = _model.generate_audio(**inputs, max_new_tokens=req.max_new_tokens,
                                        temperature=req.temperature, top_k=req.top_k, do_sample=True)
        audio_tokens = ids[0].tolist()[inputs.input_ids.shape[1]:]
        if not audio_tokens:
            raise RuntimeError('Rumik returned no audio tokens')
        codes = _model.audio_tokens_to_codes(audio_tokens)
        with torch.inference_mode():
            wav = _mimi.decode(codes.to(_mimi.device)).audio_values[0, 0]
        buf = io.BytesIO()
        sf.write(buf, wav.float().cpu().numpy(), 24000, format='WAV', subtype='PCM_16')
        return Response(content=buf.getvalue(), media_type='audio/wav')
    except Exception as e:
        print(f'[RUMIK ERROR] {type(e).__name__}: {e}', flush=True)
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f'{type(e).__name__}: {e}')
