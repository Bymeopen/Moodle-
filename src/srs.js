// Penjadwalan ulang kartu: varian SM-2 (SuperMemo-2).
const QUALITY = { again: 1, hard: 3, good: 4, easy: 5 };

export const ratings = Object.keys(QUALITY);

export const toDay = (d = new Date()) => d.toISOString().slice(0, 10);

export function addDays(day, n) {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return toDay(d);
}

/** @returns {{ease:number, interval:number, reps:number, due_at:string}} */
export function review(card, rating, today = toDay()) {
  const q = QUALITY[rating];
  let { ease, interval, reps } = card;
  if (q < 3) {
    // lupa: ulang dari awal dan muncul lagi hari ini
    reps = 0;
    interval = 0;
  } else {
    interval = reps === 0 ? 1 : reps === 1 ? 6 : Math.round(interval * ease);
    reps += 1;
  }
  ease = Math.max(1.3, ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02));
  return { ease: Math.round(ease * 100) / 100, interval, reps, due_at: addDays(today, interval) };
}
