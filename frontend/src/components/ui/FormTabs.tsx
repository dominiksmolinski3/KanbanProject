interface FormTab<T extends string> {
  id: T;
  label: string;
  disabled?: boolean;
  testId?: string;
}

interface FormTabsProps<T extends string> {
  tabs: FormTab<T>[];
  active: T;
  onChange: (id: T) => void;
}

export default function FormTabs<T extends string>({ tabs, active, onChange }: FormTabsProps<T>) {
  return (
    <div className="tab-container">
      {tabs.map(tab => (
        <button
          key={tab.id}
          type="button"
          className={`tab-btn ${active === tab.id ? 'active' : ''}`}
          onClick={() => onChange(tab.id)}
          disabled={tab.disabled}
          data-testid={tab.testId}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}
