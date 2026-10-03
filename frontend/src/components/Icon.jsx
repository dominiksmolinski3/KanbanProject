const PATHS = {
  'board': <><rect x="3.5" y="4" width="4.5" height="16" rx="1" /><rect x="10" y="4" width="4.5" height="11" rx="1" /><rect x="16.5" y="4" width="4" height="7" rx="1" /></>,
  'people': <><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20a6.5 6.5 0 0 1 13 0" /><path d="M16 4.5a3.5 3.5 0 0 1 0 7" /><path d="M18 14.5a6.5 6.5 0 0 1 3.5 5.5" /></>,
  'activity': <path d="M3 12h4l3-8 4 16 3-8h4" />,
  'flow': <><path d="M4 4v16h16" /><path d="M8 15l3.5-4 3 2.5L20 7" /></>,
  'settings': <><path d="M4 7h9M17 7h3M4 17h3M11 17h9" /><circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></>,
  'search': <><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.2-4.2" /></>,
  'plus': <path d="M12 5v14M5 12h14" />,
  'close': <path d="M6 6l12 12M18 6L6 18" />,
  'chevron-down': <path d="M6 9l6 6 6-6" />,
  'chevron-right': <path d="M9 6l6 6-6 6" />,
  'chevron-left': <path d="M15 6l-6 6 6 6" />,
  'star': <path d="M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.6l-5.1 2.7 1-5.7-4.1-4 5.7-.8z" />,
  'calendar': <><rect x="3.5" y="5" width="17" height="15" rx="2" /><path d="M3.5 10h17M8 3v4M16 3v4" /></>,
  'check': <path d="M5 12.5l4.5 4.5L19 7.5" />,
  'checklist': <path d="M4 6.5l1.5 1.5L8 5.5M4 12.5l1.5 1.5L8 11.5M4 18.5l1.5 1.5L8 17.5M11.5 7h8.5M11.5 13h8.5M11.5 19h8.5" />,
  'paperclip': <path d="M19.5 11.5l-7.6 7.6a5 5 0 0 1-7.1-7.1l8.3-8.3a3.3 3.3 0 0 1 4.7 4.7l-8.3 8.3a1.7 1.7 0 0 1-2.4-2.4l7.6-7.6" />,
  'comment': <path d="M4 5h16v11H9.5L5 20V16H4z" />,
  'lock': <><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></>,
  'globe': <><circle cx="12" cy="12" r="8.5" /><path d="M3.5 12h17M12 3.5c2.6 2.6 2.6 14.4 0 17M12 3.5c-2.6 2.6-2.6 14.4 0 17" /></>,
  'grip': <><circle cx="9" cy="6" r="1.4" /><circle cx="15" cy="6" r="1.4" /><circle cx="9" cy="12" r="1.4" /><circle cx="15" cy="12" r="1.4" /><circle cx="9" cy="18" r="1.4" /><circle cx="15" cy="18" r="1.4" /></>,
  'more': <><circle cx="6" cy="12" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="18" cy="12" r="1.5" /></>,
  'open': <path d="M8 16L16 8M9.5 8H16v6.5" />,
  'trash': <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />,
  'edit': <><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="M13.5 6.5l4 4" /></>,
  'send': <><path d="M4 12L20 4l-6 16-3-7z" /><path d="M11 13l9-9" /></>,
  'eye': <><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" /><circle cx="12" cy="12" r="3" /></>,
  'logout': <path d="M14 4h5v16h-5M10 16l-4-4 4-4M6 12h10" />,
  'user': <><circle cx="12" cy="8" r="4" /><path d="M4.5 20a7.5 7.5 0 0 1 15 0" /></>,
  'laptop': <><rect x="4" y="5" width="16" height="11" rx="1.5" /><path d="M2 19h20" /></>,
  'phone': <><rect x="7" y="3" width="10" height="18" rx="2" /><path d="M11 18h2" /></>,
  'mail': <><rect x="3.5" y="5.5" width="17" height="13" rx="2" /><path d="M4 7l8 6 8-6" /></>,
  'key': <><circle cx="8" cy="15" r="4" /><path d="M11 12l8-8M16 7l3 3" /></>,
  'download': <path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 20h14" />,
  'file': <><path d="M6 3h8l4 4v14H6z" /><path d="M14 3v4h4" /></>,
  'clock': <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>,
  'arrow-right': <path d="M5 12h14M13 6l6 6-6 6" />,
  'reopen': <><path d="M9 7H4v5" /><path d="M4.6 11.5A8 8 0 1 1 7 18" /></>,
  'link': <><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></>,
  'inbox': <><path d="M4 13h4l1.5 2.5h5L16 13h4" /><path d="M5 13l2-8h10l2 8v6H5z" /></>,
  'warning': <><path d="M12 4l9 16H3z" /><path d="M12 10v4M12 17.2v.3" /></>,
  'info': <><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5M12 7.8v.3" /></>,
  'filter': <path d="M4 5h16l-6 7.5V19l-4 1.5v-8z" />,
  'priority': <path d="M5 19v-5M10 19v-9M15 19v-12M20 19V4" />,
  'lanes': <path d="M3.5 6h17M3.5 12h17M3.5 18h17" />,
};

const FILLED_ICONS = new Set(['grip', 'more']);
const DIRECTIONAL_ICONS = new Set(['chevron-right', 'chevron-left', 'open', 'send', 'logout', 'arrow-right']);

function Icon({ name, size = 'md', filled = FILLED_ICONS.has(name), className = '' }) {
  const classes = ['kb-icon', `kb-icon--${size}`];
  if (filled) classes.push('kb-icon--fill');
  if (DIRECTIONAL_ICONS.has(name)) classes.push('kb-icon--dir');
  if (className) classes.push(className);
  return (
    <svg className={classes.join(' ')} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {PATHS[name]}
    </svg>
  );
}

export default Icon;
