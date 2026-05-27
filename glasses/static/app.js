/**
 * MetaConnect — Ray-Ban Meta Glasses Client
 * Connects to Claude AI Assistant bridge via WebSocket
 * Always-on voice with "Hey Claude" wake word — fully hands-free
 */

// =====================================================
// CONFIG
// =====================================================
const WS_URL = `ws://${window.location.host}/ws`;
const API_URL = `/api/send`;
const HEALTH_URL = `/api/health`;
const MAX_CARDS = 15;
const RECONNECT_DELAY = 3000;

// Wake word variants — catches common speech-to-text interpretations
const WAKE_WORDS = [
    'hey claude', 'hey claud', 'hey clod', 'a claude', 'hey cloud',
    'hey clawed', 'hey klaud', 'ok claude', 'okay claude'
];

// =====================================================
// DOM
// =====================================================
const statusDot = document.getElementById('status-dot');
const statusText = document.getElementById('status-text');
const cards = document.getElementById('cards');
const listening = document.getElementById('listening');
const thinking = document.getElementById('thinking');
const micBtn = document.getElementById('mic-btn');
const textInput = document.getElementById('text-input');
const sendBtn = document.getElementById('send-btn');
const hud = document.getElementById('hud');

// =====================================================
// STATE
// =====================================================
let ws = null;
let wsConnected = false;
let recognition = null;
let alwaysOn = false;       // Continuous listening mode
let awaitingCommand = false; // Wake word heard, waiting for command
let isProcessing = false;    // Waiting for response from bridge

// =====================================================
// WEBSOCKET CONNECTION
// =====================================================
function connectWS() {
    try {
        ws = new WebSocket(WS_URL);

        ws.onopen = () => {
            wsConnected = true;
            setStatus('connected', 'Connected');
            addCard('Say "Hey Claude" to start.', 'system');
        };

        ws.onmessage = (event) => {
            isProcessing = false;
            thinking.classList.add('hidden');
            try {
                const data = JSON.parse(event.data);
                handleResponse(data);
            } catch (e) {
                addCard(event.data, 'assistant');
            }
            // Resume listening after response
            if (alwaysOn) {
                setTimeout(startContinuousListening, 500);
            }
        };

        ws.onclose = () => {
            wsConnected = false;
            setStatus('offline', 'Disconnected');
            setTimeout(connectWS, RECONNECT_DELAY);
        };

        ws.onerror = () => {
            wsConnected = false;
            setStatus('offline', 'Connection error');
        };
    } catch (e) {
        setStatus('offline', 'Cannot connect');
        setTimeout(connectWS, RECONNECT_DELAY);
    }
}

// =====================================================
// SEND MESSAGE
// =====================================================
function sendMessage(text) {
    if (!text.trim()) return;

    isProcessing = true;
    addCard(text, 'user');
    thinking.classList.remove('hidden');
    scrollToBottom();

    if (wsConnected && ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ message: text }));
    } else {
        fetch(API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text })
        })
        .then(r => r.json())
        .then(data => {
            isProcessing = false;
            thinking.classList.add('hidden');
            handleResponse(data);
            if (alwaysOn) setTimeout(startContinuousListening, 500);
        })
        .catch(err => {
            isProcessing = false;
            thinking.classList.add('hidden');
            addCard('Failed to reach server.', 'error');
            if (alwaysOn) setTimeout(startContinuousListening, 500);
        });
    }
}

// =====================================================
// HANDLE RESPONSE
// =====================================================
function handleResponse(data) {
    const text = data.response || data.error || 'No response';
    const emotion = data.emotion || 'neutral';

    if (data.error) {
        addCard(text, 'error');
        return;
    }

    if (text.length > 200) {
        const chunks = splitGlanceable(text);
        chunks.forEach((chunk, i) => {
            setTimeout(() => {
                addCard(chunk, 'assistant', emotion);
                scrollToBottom();
            }, i * 150);
        });
    } else {
        addCard(text, 'assistant', emotion);
    }

    scrollToBottom();
}

function splitGlanceable(text) {
    const sentences = text.match(/[^.!?]+[.!?]+/g) || [text];
    const chunks = [];
    let current = '';

    for (const sentence of sentences) {
        if ((current + sentence).length > 180) {
            if (current) chunks.push(current.trim());
            current = sentence;
        } else {
            current += sentence;
        }
    }
    if (current.trim()) chunks.push(current.trim());
    return chunks.length ? chunks : [text];
}

// =====================================================
// CARD MANAGEMENT
// =====================================================
function addCard(text, type, emotion) {
    const card = document.createElement('div');
    card.className = `card ${type}`;

    if (emotion && emotion !== 'neutral') {
        card.classList.add(`emotion-${emotion}`);
    }

    card.textContent = text;
    cards.appendChild(card);

    while (cards.children.length > MAX_CARDS) {
        cards.removeChild(cards.firstChild);
    }

    scrollToBottom();
}

function scrollToBottom() {
    requestAnimationFrame(() => {
        hud.scrollTop = hud.scrollHeight;
    });
}

// =====================================================
// STATUS
// =====================================================
function setStatus(state, text) {
    statusDot.className = state;
    statusText.textContent = text;
}

// =====================================================
// VOICE — Always-on continuous listening with wake word
// =====================================================
function initSpeechRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;

    if (!SpeechRecognition) {
        console.log('Speech Recognition not supported');
        addCard('Voice not supported in this browser.', 'error');
        return;
    }

    recognition = new SpeechRecognition();
    recognition.continuous = true;       // Keep listening
    recognition.interimResults = true;   // Get partial results for faster wake word detection
    recognition.lang = 'en-US';
    recognition.maxAlternatives = 3;     // More chances to catch the wake word

    recognition.onresult = (event) => {
        // Check all results (interim + final)
        for (let i = event.resultIndex; i < event.results.length; i++) {
            const result = event.results[i];
            const transcript = result[0].transcript.toLowerCase().trim();

            // Check all alternatives for wake word
            let wakeWordFound = false;
            let fullText = transcript;

            for (let alt = 0; alt < result.length; alt++) {
                const altText = result[alt].transcript.toLowerCase().trim();
                for (const wake of WAKE_WORDS) {
                    if (altText.includes(wake)) {
                        wakeWordFound = true;
                        fullText = altText;
                        break;
                    }
                }
                if (wakeWordFound) break;
            }

            if (wakeWordFound && result.isFinal) {
                // Extract the command after the wake word
                let command = fullText;
                for (const wake of WAKE_WORDS) {
                    const idx = command.indexOf(wake);
                    if (idx !== -1) {
                        command = command.substring(idx + wake.length).trim();
                        break;
                    }
                }

                if (command.length > 1) {
                    // Got wake word + command in one phrase: "Hey Claude what time is it"
                    stopContinuousListening();
                    sendMessage(command);
                } else {
                    // Just the wake word — switch to command capture mode
                    awaitingCommand = true;
                    listening.classList.remove('hidden');
                    setStatus('connected', 'Listening...');
                    addCard('Listening...', 'system');
                }
            } else if (awaitingCommand && result.isFinal) {
                // We already heard wake word, this is the command
                const command = transcript.trim();
                if (command.length > 1) {
                    awaitingCommand = false;
                    listening.classList.add('hidden');
                    stopContinuousListening();
                    sendMessage(command);
                }
            }
        }
    };

    recognition.onerror = (event) => {
        console.log('Speech error:', event.error);

        if (event.error === 'not-allowed') {
            addCard('Microphone blocked. Allow mic access and reload.', 'error');
            alwaysOn = false;
            return;
        }

        // Auto-restart on recoverable errors
        if (alwaysOn && !isProcessing) {
            setTimeout(startContinuousListening, 1000);
        }
    };

    recognition.onend = () => {
        // Auto-restart if we're in always-on mode
        if (alwaysOn && !isProcessing) {
            setTimeout(startContinuousListening, 300);
        }
    };
}

function startContinuousListening() {
    if (!recognition || isProcessing) return;

    try {
        recognition.start();
        alwaysOn = true;
        micBtn.classList.add('active');
        setStatus('connected', 'Say "Hey Claude"...');
    } catch (e) {
        // Already running — that's fine
    }
}

function stopContinuousListening() {
    awaitingCommand = false;
    listening.classList.add('hidden');

    try {
        recognition.stop();
    } catch (e) {}
}

function toggleAlwaysOn() {
    if (alwaysOn) {
        // Turn off
        alwaysOn = false;
        micBtn.classList.remove('active');
        stopContinuousListening();
        setStatus('connected', 'Mic off');
        addCard('Voice off. Tap mic to re-enable.', 'system');
    } else {
        // Turn on — this first tap grants mic permission
        startContinuousListening();
        addCard('Always-on voice activated. Say "Hey Claude"...', 'system');
    }
}

// =====================================================
// EVENT LISTENERS
// =====================================================

// Mic button — toggles always-on mode
micBtn.addEventListener('click', toggleAlwaysOn);

// Send button (text fallback)
sendBtn.addEventListener('click', () => {
    const text = textInput.value.trim();
    if (text) {
        sendMessage(text);
        textInput.value = '';
    }
});

// Enter key
textInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        const text = textInput.value.trim();
        if (text) {
            sendMessage(text);
            textInput.value = '';
        }
    }
});

// =====================================================
// BACKGROUND KEEPALIVE SYSTEM
// =====================================================

// 1. Wake Lock — prevents screen from sleeping
let wakeLock = null;

async function requestWakeLock() {
    if ('wakeLock' in navigator) {
        try {
            wakeLock = await navigator.wakeLock.request('screen');
            document.getElementById('lock-status').textContent = '(always-on)';

            wakeLock.addEventListener('release', () => {
                document.getElementById('lock-status').textContent = '';
                // Re-acquire if page becomes visible again
            });
        } catch (e) {
            console.log('Wake lock failed:', e);
        }
    }
}

// Re-acquire wake lock when page becomes visible again
document.addEventListener('visibilitychange', async () => {
    if (document.visibilityState === 'visible') {
        await requestWakeLock();
        // Restart listening if it died in background
        if (alwaysOn && !isProcessing) {
            setTimeout(startContinuousListening, 500);
        }
        // Reconnect WebSocket if dropped
        if (!wsConnected) {
            connectWS();
        }
    }
});

// 2. Silent audio — tricks the browser into staying alive in background
function startKeepaliveAudio() {
    const audio = document.getElementById('keepalive-audio');
    if (!audio) return;

    // Create a silent audio stream using AudioContext
    try {
        const ctx = new (window.AudioContext || window.webkitAudioContext)();
        const oscillator = ctx.createOscillator();
        const gain = ctx.createGain();

        // Completely silent
        gain.gain.value = 0.001;
        oscillator.connect(gain);
        gain.connect(ctx.destination);
        oscillator.start();

        // Also create a MediaStream for the audio element
        const dest = ctx.createMediaStreamDestination();
        gain.connect(dest);
        audio.srcObject = dest.stream;
        audio.play().catch(() => {});
    } catch (e) {
        console.log('Keepalive audio failed:', e);
    }
}

// 3. Service Worker registration
async function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
        try {
            await navigator.serviceWorker.register('/static/sw.js');
            console.log('Service worker registered');
        } catch (e) {
            console.log('SW registration failed:', e);
        }
    }
}

// 4. Periodic self-ping — keeps the WebSocket and recognition alive
function startSelfPing() {
    setInterval(() => {
        // Keep WebSocket alive
        if (wsConnected && ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ ping: true }));
        }

        // Restart recognition if it silently died
        if (alwaysOn && !isProcessing && recognition) {
            try {
                // Check if recognition is actually running by restarting it
                // onend handler will auto-restart if needed
            } catch (e) {}
        }
    }, 15000);
}

// =====================================================
// HEALTH CHECK
// =====================================================
async function checkHealth() {
    try {
        const res = await fetch(HEALTH_URL);
        const data = await res.json();

        if (data.bridge === 'offline') {
            setStatus('bridge-down', 'Bridge offline');
        }
    } catch (e) {}
}

// =====================================================
// INIT
// =====================================================
initSpeechRecognition();
connectWS();
registerServiceWorker();
setInterval(checkHealth, 30000);
startSelfPing();

// First tap: grants mic + screen lock + starts keepalive audio + goes always-on
document.addEventListener('click', async function autoStart() {
    if (!alwaysOn && recognition) {
        startContinuousListening();
        addCard('Always-on voice activated. Say "Hey Claude"...', 'system');
    }
    await requestWakeLock();
    startKeepaliveAudio();
    document.removeEventListener('click', autoStart);
}, { once: true });
