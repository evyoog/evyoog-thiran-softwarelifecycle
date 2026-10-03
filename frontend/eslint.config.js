// VYB-0912 (F12): ESLint for the frontend. `npm run lint` (also run by CI) must pass with no errors.
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  // The generated API types are tool output (npm run generate-api); never lint or hand-edit them.
  { ignores: ['dist', 'node_modules', 'src/shared/api/generated'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2022, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      // The two classic rules. eslint-plugin-react-hooks 7 also ships a set of React Compiler rules
      // (refs, set-state-in-effect, purity, static-components, immutability, use-memo,
      // preserve-manual-memoization, incompatible-library). They are off: this app does not use the
      // React Compiler, and they flag patterns that are correct here (for example resetting a field
      // when its prop is cleared) where "fixing" them means restructuring components that cannot be
      // checked without a browser. Turning them on is its own decision, not part of adding lint.
      // `set.has(k) ? set.delete(k) : set.add(k)` is how this code toggles membership; it is used in five
      // places and is clear. `_`-prefixed names are the "omit these keys" destructuring idiom.
      '@typescript-eslint/no-unused-expressions': ['error', { allowTernary: true }],
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_', ignoreRestSiblings: true,
      }],
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
  {
    // Config and build scripts run in Node, not the browser.
    files: ['*.config.{js,ts}', 'vite.config.ts'],
    languageOptions: { globals: globals.node },
  },
)
