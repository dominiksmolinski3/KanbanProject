import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import Backend from 'i18next-http-backend';
import LanguageDetector from 'i18next-browser-languagedetector';

i18n
  // Load translations from /public/locales
  .use(Backend)
  // Detect user language
  .use(LanguageDetector)
  // Pass i18n instance to react-i18next
  .use(initReactI18next)
  // Initialize
  .init({
    fallbackLng: 'en',
    debug: import.meta.env.DEV,
    interpolation: {
      escapeValue: false, // React already escapes values
    },
    detection: {
      order: ['querystring', 'localStorage', 'navigator', 'htmlTag'],
      caches: ['localStorage'],
    },
    backend: {
      loadPath: '/locales/{{lng}}/{{ns}}.json',
    }
  });

// Locales written right-to-left. Arabic ships as a supported language, so the document has to
// say so — without dir the whole board renders left-to-right and reads as broken Arabic.
const RTL_LANGUAGES = ['ar', 'fa', 'he', 'ur'];

function applyDocumentDirection(language) {
  if (typeof document === 'undefined') return;
  const base = (language || 'en').split('-')[0];
  document.documentElement.lang = base;
  document.documentElement.dir = RTL_LANGUAGES.includes(base) ? 'rtl' : 'ltr';
}

i18n.on('languageChanged', applyDocumentDirection);
applyDocumentDirection(i18n.language);

export default i18n;