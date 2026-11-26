import { getAI, getGenerativeModel, GoogleAIBackend } from 'firebase/ai';

const SYSTEM_INSTRUCTION =
  'You are UniCore Academic Tutor. Give clear, concise, practical study answers in the user language (Arabic if prompted in Arabic or requested). Keep answers structured and well-formatted.';

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
        ? 'يرجى إضافة App ID في إعدادات الربط (أيقونة الترس في شاشة الدخول) أو متغير VITE_FIREBASE_APP_ID لتشغيل المساعد الذكي.'
        : 'Please set a Firebase App ID in Connection Settings or VITE_FIREBASE_APP_ID to use AI Tutor.'
    );
  }

  const ai = getAI(app, { backend: new GoogleAIBackend() });
  const localizedPrompt =
    language === 'ARABIC'
      ? `أجب باللغة العربية الواضحة وبأسلوب تعليمي مختصر ومنظم:\n${prompt}`
      : prompt;

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
          onPartial(fullText);
        }
      }

      if (fullText.trim()) {
        return fullText;
      }

      const unary = await model.generateContent(localizedPrompt);
      const unaryText = unary?.response?.text?.() || '';
      if (unaryText.trim()) {
        onPartial(unaryText);
        return unaryText;
      }
    } catch (err) {
      if (err?.name === 'AbortError') throw err;
      const msg = String(err?.message || '').toLowerCase();
      if (msg.includes('403') || msg.includes('app check') || msg.includes('quota') || msg.includes('429')) {
        throw err;
      }
      lastError = err;
    }
  }

  throw lastError || new Error('AI Tutor failed to generate a response.');
}
