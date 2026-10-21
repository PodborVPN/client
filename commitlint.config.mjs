// Проверка заголовков коммитов в CI: Conventional Commits, текст — по-русски.
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // Регистр русского текста не проверяем.
    'subject-case': [0],
    'header-max-length': [2, 'always', 120],
    'body-max-line-length': [0],
  },
};
