let sharedAudioCtx = null;

function getAudioContext() {
  if (typeof window === 'undefined') return null;
  const AudioCtx = window.AudioContext || window.webkitAudioContext;
  if (!AudioCtx) return null;
  if (!sharedAudioCtx || sharedAudioCtx.state === 'closed') {
    sharedAudioCtx = new AudioCtx();
  }
  return sharedAudioCtx;
}

/**
 * Plays the distinctive UniCore 4-note harmonic academic alert chime.
 * Safe no-op in environments or browser policies blocking autoplay audio.
 */
export async function playUniCoreChime() {
  try {
    const ctx = getAudioContext();
    if (!ctx) return false;
    if (ctx.state === 'suspended') {
      await ctx.resume().catch(() => {});
    }
    if (ctx.state !== 'running') return false;

    const now = ctx.currentTime;
    // UniCore signature intervals: C5 (523.25Hz) -> E5 (659.25Hz) -> G5 (783.99Hz) -> C6 (1046.50Hz)
    const notes = [
      { freq: 523.25, start: 0.0, duration: 0.16, peak: 0.13 },
      { freq: 659.25, start: 0.11, duration: 0.18, peak: 0.14 },
      { freq: 783.99, start: 0.22, duration: 0.2, peak: 0.15 },
      { freq: 1046.5, start: 0.34, duration: 0.36, peak: 0.12 }
    ];

    notes.forEach(({ freq, start, duration, peak }) => {
      const osc = ctx.createOscillator();
      const subOsc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now + start);

      subOsc.type = 'triangle';
      subOsc.frequency.setValueAtTime(freq * 2, now + start);

      const subGain = ctx.createGain();
      subGain.gain.setValueAtTime(0.18, now + start);

      gain.gain.setValueAtTime(0.0001, now + start);
      gain.gain.exponentialRampToValueAtTime(peak, now + start + 0.025);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + start + duration);

      osc.connect(gain);
      subOsc.connect(subGain);
      subGain.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now + start);
      subOsc.start(now + start);
      osc.stop(now + start + duration + 0.03);
      subOsc.stop(now + start + duration + 0.03);
    });
    return true;
  } catch {
    return false;
  }
}
