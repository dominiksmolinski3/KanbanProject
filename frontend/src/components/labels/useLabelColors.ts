import { useEffect, useState } from 'react';
import { getAllLabels } from '../../services/api';
import { readStoredLabelColors } from '../../board/cardModel';
import { DEFAULT_LABEL_COLOR, PREDEFINED_LABELS } from './labelPalette';

export function useLabelColors() {
  const [existingLabels, setExistingLabels] = useState<string[]>([]);
  const [labelColors, setLabelColors] = useState<Record<string, string>>({});

  useEffect(() => {
    const fetchExistingLabels = async () => {
      try {
        const labelsData = await getAllLabels();
        setExistingLabels(labelsData);

        const mergedColors = { ...readStoredLabelColors() };
        PREDEFINED_LABELS.forEach(label => {
          mergedColors[label.name] = label.color;
        });

        setLabelColors(mergedColors);
      } catch (error) {
        console.error('Error fetching existing labels:', error);
      }
    };

    fetchExistingLabels();
  }, []);

  useEffect(() => {
    if (Object.keys(labelColors).length > 0) {
      localStorage.setItem('labelColors', JSON.stringify(labelColors));
    }
  }, [labelColors]);

  const colorOf = (labelName: string): string => {
    if (labelColors[labelName]) {
      return labelColors[labelName];
    }

    const predefined = PREDEFINED_LABELS.find(l => l.name === labelName);
    return predefined?.color || DEFAULT_LABEL_COLOR;
  };

  const rememberColor = (labelName: string, color: string) =>
    setLabelColors({ ...labelColors, [labelName]: color });

  const rememberLabel = (labelName: string) => {
    if (!existingLabels.includes(labelName)) {
      setExistingLabels([...existingLabels, labelName]);
    }
  };

  return { existingLabels, colorOf, rememberColor, rememberLabel };
}
