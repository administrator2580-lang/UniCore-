import { getAI, getGenerativeModel, GoogleAIBackend } from 'firebase/ai';
import { formatAiReadableText } from '../security/validators';

const SYSTEM_INSTRUCTION =
  'You are UniCore Academic Tutor. Give clear, concise, practical study answers in the user language (Arabic if prompted in Arabic or requested). CRITICAL FORMATTING RULE: Never output raw LaTeX, TeX dollar signs ($ or $$), backslashes (such as \\Omega, \\frac, \\text, \\times), or raw Markdown clutter (### or **). Always write math expressions using clear Unicode symbols (Ω, ×, ÷, =, √, ², ³, °) and simple bullet points (•) so students can read equations effortlessly.';

export async function askAiTutorStream({
  app,
  config,
  prompt,
  language,
  signal,
  onPartial
}) {
  if (!config?.appId && !app?.options?.appId) {
    throw new Error(
      language === 'ARABIC'
        ? 'يرجى التواصل مع السوبر أدمن لضبط App ID أو ضبط متغير VITE_FIREBASE_APP_ID لتشغيل المساعد الذكي.'
        : 'Please ask a Super Admin to configure App ID or set VITE_FIREBASE_APP_ID to use AI Tutor.'
    );
  }

  const ai = getAI(app, { backend: new GoogleAIBackend() });
  const localizedPrompt =
    language === 'ARABIC'
      ? `أجب باللغة العربية الواضحة وبأسلوب تعليمي مختصر ومنظم بدون استخدام رموز LaTeX أو علامات $ أو *** أو ### (استخدم الرموز المباشرة مثل Ω و × و = فقط):\n${prompt}`
      : `Answer clearly without raw LaTeX ($ or $$) or Markdown hashes (### or **). Use clean Unicode symbols (Ω, ×, ÷, √, ², =):\n${prompt}`;

  const modelCandidates = ['gemini-3.5-flash-lite', 'gemini-3.8-flash', 'gemini-2.5-flash'];
  let lastError = null;

  for (const modelName of modelCandidates) {
    if (signal?.aborted) {
      throw new DOMException('Cancelled', 'AbortError');
    }
    try {
      const model = getGenerativeModel(ai, {
        model: modelName,
        systemInstruction: SYSTEM_INSTRUCTION,
        generationConfig: {
          temperature: 0.4,
          maxOutputTokens: 1024
        }
      });

      const streamResult = await model.generateContentStream(localizedPrompt);
      let fullText = '';
      for await (const chunk of streamResult.stream) {
        if (signal?.aborted) {
          throw new DOMException('Cancelled', 'AbortError');
        }
        const piece = typeof chunk.text === 'function' ? chunk.text() : '';
        if (piece) {
          fullText += piece;
          onPartial(formatAiReadableText(fullText));
        }
      }

      if (fullText.trim()) {
        return formatAiReadableText(fullText);
      }

      const unary = await model.generateContent(localizedPrompt);
      const unaryText = unary?.response?.text?.() || '';
      if (unaryText.trim()) {
        const cleaned = formatAiReadableText(unaryText);
        onPartial(cleaned);
        return cleaned;
      }
    } catch (err) {
      if (err?.name === 'AbortError') throw err;
      const msg = String(err?.message || '').toLowerCase();
      if (msg.includes('app check') || msg.includes('401')) {
        throw new Error(
          language === 'ARABIC'
            ? 'خدمة App Check مفعّلة على Firebase AI Logic (خطأ 401). للحل: افتح Firebase Console ← App Check ← تبويب APIs ← اختر Firebase AI Logic واضغط Unenforce، أو أضف Debug Token / reCAPTCHA Key من إعدادات السوبر أدمن.'
            : 'Firebase AI Logic App Check rejected this request (401). Fix: open Firebase Console -> App Check -> APIs -> Firebase AI Logic and click Unenforce, or configure an App Check Debug Token / reCAPTCHA key in Super Admin settings.'
        );
      }
      if (msg.includes('403') || msg.includes('quota') || msg.includes('429')) {
        throw err;
      }
      lastError = err;
    }
  }

  throw lastError || new Error('AI Tutor failed to generate a response.');
}
