import { isLocale } from './i18n'
import { useLocale } from './LocaleProvider'

/** VYB-0958 (D45): the language choice. Its label is in the current language; each language is named in itself. */
export function LocaleSwitcher({ compact }: { compact?: boolean }) {
  const { locale, setLocale, offered, t } = useLocale()
  return (
    <select
      className="select"
      aria-label={t('locale.label')}
      value={locale}
      style={compact ? { padding: '4px 6px', fontSize: 12, width: 'auto' } : undefined}
      onChange={(e) => { if (isLocale(e.target.value)) setLocale(e.target.value) }}
    >
      {offered.map((l) => <option key={l.code} value={l.code} lang={l.code === 'en-XA' ? 'en' : l.code}>{l.label}</option>)}
    </select>
  )
}
