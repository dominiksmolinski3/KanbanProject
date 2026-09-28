(function () {
  var STORAGE_KEY = 'kanban-theme';
  var CHANGE_EVENT = 'kanban-theme-change';
  var system = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function preference() {
    try {
      var stored = window.localStorage.getItem(STORAGE_KEY);
      return stored === 'light' || stored === 'dark' ? stored : 'system';
    } catch {
      return 'system';
    }
  }

  function apply() {
    var choice = preference();
    if (choice === 'system') {
      choice = system && system.matches ? 'dark' : 'light';
    }
    document.documentElement.setAttribute('data-theme', choice);
  }

  apply();
  window.addEventListener(CHANGE_EVENT, apply);
  window.addEventListener('storage', function (event) {
    if (event.key === STORAGE_KEY) apply();
  });
  if (system && system.addEventListener) {
    system.addEventListener('change', apply);
  }
})();
