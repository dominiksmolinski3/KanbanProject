import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

interface QrCodeProps {
  value: string;
  label: string;
}

const QUIET_ZONE = 4;

export default function QrCode({ value, label }: QrCodeProps) {
  const path = useMemo(() => {
    const code = qrcode(0, 'L');
    code.addData(value, 'Byte');
    code.make();
    const size = code.getModuleCount();
    let drawn = '';
    for (let row = 0; row < size; row++) {
      for (let column = 0; column < size; column++) {
        if (code.isDark(row, column)) drawn += `M${column + QUIET_ZONE} ${row + QUIET_ZONE}h1v1h-1z`;
      }
    }
    return { size: size + QUIET_ZONE * 2, drawn };
  }, [value]);

  return (
    <svg className="qr-code" viewBox={`0 0 ${path.size} ${path.size}`} role="img" aria-label={label}
      shapeRendering="crispEdges">
      <rect width={path.size} height={path.size} fill="#fff" />
      <path d={path.drawn} fill="#000" />
    </svg>
  );
}
