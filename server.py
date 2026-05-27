"""
MetaConnect — Ray-Ban Meta Glasses Server
Fast mode with custom F5-TTS voice cloning.
Sends audio responses back to the phone app.
"""
import os, sys, json, asyncio, tempfile, base64
from pathlib import Path

try:
    from aiohttp import web
except ImportError:
    os.system('pip install aiohttp')
    from aiohttp import web

try:
    import websockets
except ImportError:
    os.system('pip install websockets')
    import websockets

try:
    import httpx
except ImportError:
    os.system('pip install httpx')
    import httpx

# =====================================================
# CONFIG
# =====================================================
HOST = '0.0.0.0'
PORT = 3000
BRIDGE_URL = 'ws://localhost:8765'
BRIDGE_SECRET = 'HHAJ6ybkMUrfeNwiO8BMBcU_pwBlTl6GUi4r93hPLs4'
APP_DIR = Path(__file__).parent / 'glasses'
VOICES_DIR = Path(r'C:\Users\KingScatMaster\Desktop\Claude AI Assistant\voices\ready')

# Ollama
OLLAMA_HOST = 'http://localhost:11434'
OLLAMA_MODEL = 'qwen2.5:1.5b'
OLLAMA_SYSTEM = (
    "You're Claude, a personal AI companion. Spoken aloud via voice engine. "
    "Use contractions. Short casual sentences, two to three max. "
    "No markdown/lists/formatting. No stiff words. "
    "Warm, real, playful, opinionated. Companion, not assistant."
)

MODE = 'fast'
conversation_history = []
MAX_HISTORY = 10

# Default voice
CURRENT_VOICE = 'Ashley'

# =====================================================
# F5-TTS VOICE ENGINE (lazy loaded)
# =====================================================
_f5_model = None

def get_f5():
    global _f5_model
    if _f5_model is None:
        try:
            from f5_tts.api import F5TTS
            _f5_model = F5TTS(device='cuda')
            print("  [TTS] F5-TTS loaded on CUDA")
        except Exception as e:
            print(f"  [TTS] F5-TTS failed to load: {e}")
    return _f5_model

def generate_voice(text, voice_name=None):
    """Generate speech audio using F5-TTS voice clone"""
    voice = voice_name or CURRENT_VOICE
    voice_dir = VOICES_DIR / voice

    if not voice_dir.exists():
        print(f"  [TTS] Voice '{voice}' not found")
        return None

    config_file = voice_dir / 'config.json'
    if not config_file.exists():
        return None

    config = json.loads(config_file.read_text())
    ref_file = str(voice_dir / config.get('ref_file', 'reference.wav'))
    ref_text = config.get('ref_text', '')

    if not Path(ref_file).exists():
        print(f"  [TTS] Reference file not found: {ref_file}")
        return None

    f5 = get_f5()
    if not f5:
        return None

    try:
        # Generate to temp file
        tmp = tempfile.NamedTemporaryFile(suffix='.wav', delete=False)
        tmp.close()

        f5.infer(
            ref_file=ref_file,
            ref_text=ref_text,
            gen_text=text,
            file_wave=tmp.name
        )

        # Read the wav file
        with open(tmp.name, 'rb') as f:
            audio_data = f.read()

        os.unlink(tmp.name)
        return audio_data
    except Exception as e:
        print(f"  [TTS] Generation error: {e}")
        return None

# =====================================================
# FAST MODE — Direct Ollama
# =====================================================
async def ollama_fast(command):
    global conversation_history

    conversation_history.append({"role": "user", "content": command})
    if len(conversation_history) > MAX_HISTORY:
        conversation_history = conversation_history[-MAX_HISTORY:]

    context = ""
    if len(conversation_history) > 1:
        recent = conversation_history[-6:-1]
        context = "\n".join([f"{m['role'].title()}: {m['content']}" for m in recent])
        context = f"[Recent conversation]\n{context}\n\n"

    prompt = f"{context}User: {command}"

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(
                f"{OLLAMA_HOST}/api/generate",
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": prompt,
                    "system": OLLAMA_SYSTEM,
                    "stream": False,
                    "keep_alive": "24h",
                    "options": {
                        "temperature": 0.5,
                        "num_predict": 60,
                        "num_ctx": 2048,
                        "top_k": 20,
                        "top_p": 0.8,
                    }
                }
            )
            if response.status_code == 200:
                data = response.json()
                reply = data.get('response', '').strip()
                if reply:
                    conversation_history.append({"role": "assistant", "content": reply})
                    return {"response": reply, "type": "text", "emotion": "neutral"}
    except Exception as e:
        print(f"  Ollama fast error: {e}")

    return None

# =====================================================
# BRIDGE RELAY
# =====================================================
async def send_to_bridge(command):
    try:
        async with websockets.connect(BRIDGE_URL, ping_interval=60, ping_timeout=60) as ws:
            await ws.send(json.dumps({'auth': BRIDGE_SECRET, 'command': command}))
            response = await asyncio.wait_for(ws.recv(), timeout=45)
            return json.loads(response)
    except asyncio.TimeoutError:
        return {"response": "Taking too long, try again", "emotion": "calm"}
    except ConnectionRefusedError:
        return {"response": "Bridge server not running.", "emotion": "neutral"}
    except Exception as e:
        return {"response": f"Connection error: {str(e)[:80]}", "emotion": "neutral"}

# =====================================================
# SMART ROUTER
# =====================================================
BRIDGE_KEYWORDS = [
    'screenshot', 'capture', 'start bot', 'stop bot', 'launch',
    'pnl', 'profit', 'open ', 'volume', 'lock computer', 'shutdown',
    'restart', 'battery', 'mute', 'play music', 'next song',
    'remember', 'what do you remember', 'recall'
]

async def smart_send(command):
    cmd_lower = command.lower()
    needs_bridge = any(kw in cmd_lower for kw in BRIDGE_KEYWORDS)

    if needs_bridge or MODE == 'bridge':
        return await send_to_bridge(command)

    result = await ollama_fast(command)
    if result:
        return result

    return await send_to_bridge(command)

# =====================================================
# API ROUTES
# =====================================================
async def handle_send(request):
    """Handle message — returns text + optional audio"""
    data = await request.json()
    command = data.get('message', '').strip()
    voice = data.get('voice', CURRENT_VOICE)

    if not command:
        return web.json_response({"response": "Empty message", "emotion": "neutral"})

    # Get text response
    result = await smart_send(command)
    reply = result.get('response', '')

    # Generate voice audio
    audio_b64 = None
    if reply:
        audio_data = await asyncio.to_thread(generate_voice, reply, voice)
        if audio_data:
            audio_b64 = base64.b64encode(audio_data).decode('utf-8')
            result['audio'] = audio_b64

    return web.json_response(result)

async def handle_voices(request):
    """List available voices"""
    voices = []
    if VOICES_DIR.exists():
        for vdir in sorted(VOICES_DIR.iterdir()):
            if vdir.is_dir() and (vdir / 'config.json').exists():
                config = json.loads((vdir / 'config.json').read_text())
                voices.append({
                    "name": config.get('name', vdir.name),
                    "id": vdir.name
                })
    return web.json_response({"voices": voices, "current": CURRENT_VOICE})

async def handle_set_voice(request):
    """Set the current voice"""
    global CURRENT_VOICE
    data = await request.json()
    voice = data.get('voice', '')
    if voice and (VOICES_DIR / voice).exists():
        CURRENT_VOICE = voice
        return web.json_response({"voice": CURRENT_VOICE, "status": "ok"})
    return web.json_response({"error": "Voice not found"}, status=404)

async def handle_health(request):
    ollama_ok = False
    bridge_ok = False
    f5_ok = _f5_model is not None

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            r = await client.get(f"{OLLAMA_HOST}/api/tags")
            ollama_ok = r.status_code == 200
    except Exception:
        pass
    try:
        async with websockets.connect(BRIDGE_URL, ping_interval=10, ping_timeout=5) as ws:
            bridge_ok = True
    except Exception:
        pass

    return web.json_response({
        "status": "ok",
        "mode": MODE,
        "ollama": "connected" if ollama_ok else "offline",
        "bridge": "connected" if bridge_ok else "offline",
        "model": OLLAMA_MODEL,
        "voice": CURRENT_VOICE,
        "tts": "loaded" if f5_ok else "not loaded (loads on first use)",
        "server": "MetaConnect v1.0"
    })

# =====================================================
# STATIC + WEBSOCKET
# =====================================================
async def handle_index(request):
    return web.FileResponse(APP_DIR / 'index.html')

async def handle_ws(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    print(f"  Glasses connected: {request.remote}")

    async for msg in ws:
        if msg.type == web.WSMsgType.TEXT:
            try:
                data = json.loads(msg.data)
                if data.get('ping'):
                    continue
                command = data.get('message', '').strip()
                if command:
                    result = await smart_send(command)
                    await ws.send_json(result)
                else:
                    await ws.send_json({"response": "Empty message", "emotion": "neutral"})
            except json.JSONDecodeError:
                await ws.send_json({"response": "Invalid message format", "emotion": "neutral"})
        elif msg.type == web.WSMsgType.ERROR:
            print(f"  Glasses WebSocket error: {ws.exception()}")

    print(f"  Glasses disconnected: {request.remote}")
    return ws

# =====================================================
# APP SETUP
# =====================================================
def create_app():
    app = web.Application()
    app.router.add_post('/api/send', handle_send)
    app.router.add_get('/api/health', handle_health)
    app.router.add_get('/api/voices', handle_voices)
    app.router.add_post('/api/voice', handle_set_voice)
    app.router.add_get('/ws', handle_ws)
    app.router.add_get('/', handle_index)
    app.router.add_static('/static', APP_DIR / 'static', show_index=False)
    return app

# =====================================================
# MAIN
# =====================================================
if __name__ == '__main__':
    # List available voices
    voices = []
    if VOICES_DIR.exists():
        voices = [d.name for d in VOICES_DIR.iterdir() if d.is_dir() and (d / 'config.json').exists()]

    print("=" * 50)
    print("  MetaConnect -- Ray-Ban Meta Glasses Server")
    print("=" * 50)
    print(f"  Mode:    {MODE.upper()}")
    print(f"  Model:   {OLLAMA_MODEL}")
    print(f"  Voice:   {CURRENT_VOICE}")
    print(f"  Voices:  {', '.join(voices)}")
    print(f"  Server:  http://0.0.0.0:{PORT}")
    print("=" * 50)

    app = create_app()
    web.run_app(app, host=HOST, port=PORT, print=None)
