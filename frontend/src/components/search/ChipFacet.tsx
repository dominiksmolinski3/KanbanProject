interface ChipFacetProps<V extends string | number> {
  legend: string;
  options: { value: V; label: string }[];
  selected: V[];
  onToggle: (value: V) => void;
}

export default function ChipFacet<V extends string | number>({ legend, options, selected, onToggle }: ChipFacetProps<V>) {
  return (
    <fieldset className="task-search-facet">
      <legend>{legend}</legend>
      <div className="task-search-chips">
        {options.map(({ value, label }) => (
          <button
            type="button"
            key={value}
            className={`task-search-chip ${selected.includes(value) ? 'active' : ''}`}
            aria-pressed={selected.includes(value)}
            onClick={() => onToggle(value)}
          >
            {label}
          </button>
        ))}
      </div>
    </fieldset>
  );
}
