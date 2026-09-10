#!/usr/bin/env python3
from __future__ import annotations
import csv
import json
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def percentile(values, p):
    if not values:
        return 0.0
    xs=sorted(values)
    if len(xs)==1:
        return xs[0]
    pos=(len(xs)-1)*p
    lo=int(math.floor(pos)); hi=int(math.ceil(pos))
    if lo==hi: return xs[lo]
    return xs[lo]*(hi-pos)+xs[hi]*(pos-lo)


def ort_profiles(files, out):
    rows=[]
    by_op=defaultdict(lambda:[0,0.0])
    by_provider=defaultdict(lambda:[0,0.0])
    parsed=0
    for f in files:
        try:
            data=json.loads(f.read_text(encoding='utf-8', errors='replace'))
        except Exception:
            continue
        events=data if isinstance(data,list) else data.get('traceEvents',[]) if isinstance(data,dict) else []
        node_count=0; total_us=0.0
        for e in events:
            if not isinstance(e,dict): continue
            dur=e.get('dur')
            if not isinstance(dur,(int,float)): continue
            args=e.get('args') if isinstance(e.get('args'),dict) else {}
            cat=str(e.get('cat',''))
            op=str(args.get('op_name') or args.get('op') or '')
            provider=str(args.get('provider') or args.get('execution_provider') or '')
            name=str(e.get('name',''))
            # ORT node events normally have cat=Node and *_kernel_time names.
            if cat.lower()!='node' and not op and 'kernel_time' not in name:
                continue
            node_count += 1
            total_us += float(dur)
            key=op or name or '<unknown>'
            by_op[key][0]+=1; by_op[key][1]+=float(dur)/1000.0
            by_provider[provider or '<unknown>'][0]+=1; by_provider[provider or '<unknown>'][1]+=float(dur)/1000.0
        if node_count:
            parsed+=1
            rows.append((f.name,node_count,total_us/1000.0))
    if not parsed:
        return
    out.append('=== ONNX Runtime node profiles ===')
    for name,count,total in rows:
        out.append(f'{name}: node_events={count} node_total_ms={total:.3f}')
    out.append('Top operators by accumulated node time:')
    for op,(count,total) in sorted(by_op.items(), key=lambda kv: kv[1][1], reverse=True)[:25]:
        out.append(f'  {op}: total_ms={total:.3f} count={count}')
    out.append('Providers:')
    for provider,(count,total) in sorted(by_provider.items(), key=lambda kv: kv[1][1], reverse=True):
        out.append(f'  {provider}: total_ms={total:.3f} count={count}')
    out.append('')


def litert_profiles(files, out):
    groups=defaultdict(list)
    sources=defaultdict(set)
    for f in files:
        try:
            with f.open(newline='', encoding='utf-8', errors='replace') as fh:
                r=csv.DictReader(fh)
                if not r.fieldnames or 'graph' not in r.fieldnames or 'ms' not in r.fieldnames:
                    continue
                for row in r:
                    try: ms=float(row['ms'])
                    except Exception: continue
                    g=row.get('graph') or f.stem
                    groups[g].append(ms)
                    sources[g].add(f.name)
        except Exception:
            pass
    if not groups:
        return
    out.append('=== LiteRT graph-invoke profiles ===')
    out.append('NOTE: LiteRT CSV records graph invoke time for fixed and selected-signature XNNPACK paths; it is not per-op attribution.')
    for g,vals in sorted(groups.items()):
        out.append(
            f'{g}: count={len(vals)} total_ms={sum(vals):.3f} '
            f'median_ms={statistics.median(vals):.3f} p95_ms={percentile(vals,.95):.3f} '
            f'min_ms={min(vals):.3f} max_ms={max(vals):.3f}'
        )
    out.append('')


def qnn_profiles(files, out):
    if not files:
        return
    out.append('=== QNN profiler files ===')
    for f in files:
        out.append(f'{f.name}: bytes={f.stat().st_size}')
        # Keep parsing deliberately conservative: QNN optrace CSV schema varies by QAIRT.
        try:
            first=[]
            with f.open(encoding='utf-8', errors='replace') as fh:
                for _ in range(3):
                    line=fh.readline().strip()
                    if not line: break
                    first.append(line[:500])
            for line in first:
                out.append(f'  {line}')
        except Exception:
            pass
    out.append('Raw QNN optrace files are preserved unchanged for detailed inspection.')
    out.append('')


def main():
    if len(sys.argv)!=2:
        print('usage: analyze_deep_profiles.py PROFILE_DIR', file=sys.stderr)
        return 2
    root=Path(sys.argv[1])
    if not root.is_dir():
        print(f'not a directory: {root}', file=sys.stderr)
        return 2
    files=[p for p in root.rglob('*') if p.is_file()]
    out=[f'Supertonic FIX10 Deep Profiler summary', f'Directory: {root}', f'Files: {len(files)}', '']
    ort_profiles([p for p in files if p.suffix.lower()=='.json' and ('ort_' in p.name.lower() or 'profile' in p.name.lower())], out)
    litert_profiles([p for p in files if p.suffix.lower()=='.csv' and p.name.lower().startswith('litert_')], out)
    qnn_profiles([p for p in files if p.suffix.lower() in ('.csv','.json') and p.name.lower().startswith('qnn_')], out)
    if len(out)<=4:
        out.append('No recognized profile files found yet.')
    text='\n'.join(out)+'\n'
    (root/'SUMMARY.txt').write_text(text, encoding='utf-8')
    print(text)
    return 0

if __name__=='__main__':
    raise SystemExit(main())
