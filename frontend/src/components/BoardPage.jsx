import React from 'react';
import Bench from './Bench';
import Board from './Board';

// Bench and Board are only ever used together, so they split as one chunk rather than two.
function BoardPage() {
  return (
    <div className="app">
      <Bench />
      <Board />
    </div>
  );
}

export default BoardPage;
