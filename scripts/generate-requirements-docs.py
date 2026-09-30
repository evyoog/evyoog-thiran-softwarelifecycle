#!/usr/bin/env python3
"""Regenerates the requirement lists under docs/ and test-cases/ from BUILD-REGISTER.md.

BUILD-REGISTER.md is the source of truth for what each requirement is and its status. The files
this writes are views of it, grouped by feature, with the automated tests that name each
requirement (test methods called VYBnnnn_ACn_...). Do not edit them by hand: change the register
(or a test name), run this script, and commit the result.

    python3 scripts/generate-requirements-docs.py          # rewrite the files
    python3 scripts/generate-requirements-docs.py --check  # exit 1 if any file is out of date

Fails if a register row has a capability this script does not know, so a new capability cannot
silently go missing from the docs.
"""
import collections
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))
REGISTER = os.path.join(ROOT, 'BUILD-REGISTER.md')

# feature folder -> (title, spec section or None, capabilities in the register that belong to it)
FEATURES = collections.OrderedDict([
    ('application-shell', ('Application shell', '7.0', ['Shell', 'Identity (FE)'])),
    ('home', ('Home', '7.1', ['Home'])),
    ('my-work', ('My Work', '7.2', ['My Work (FE)', 'Tasks', 'Notifications'])),
    ('portfolio', ('Portfolio', '7.3', ['Portfolio', 'Portfolio (FE)'])),
    ('requirements', ('Requirements', '7.4', ['Requirements', 'API', 'Grid', 'Detail panel', 'Authoring', 'Documents',
                                              'Import queue', 'Import queue (FE)'])),
    ('design', ('Design', '7.5', ['Design (BE)', 'Design (FE)'])),
    ('analytics', ('Analytics', '7.6', ['Analytics', 'Analytics (FE)'])),
    ('quality', ('Quality', '7.7', ['Quality (FE)', 'Evidence', 'Defects'])),
    ('delivery', ('Delivery', '7.8', ['Briefs', 'Signals', 'Delivery (FE)'])),
    ('releases', ('Releases', '7.9', ['Releases', 'Releases (FE)', 'Baselines', 'Deployment'])),
    ('administration', ('Administration', '7.10', ['Users and access', 'Service accounts', 'Audit', 'Tenant lifecycle',
                                                   'Integrations', 'Administration (FE)'])),
    # capabilities the specification does not give a screen of their own
    ('trace-graph', ('Trace graph', None, ['Trace graph'])),
    ('gap-detection', ('Gap detection and AI detectors', '6', ['Detection', 'AI detectors', 'Embeddings'])),
    ('review-and-change-control', ('Review, clarification and change requests', None,
                                   ['Review', 'Clarification', 'Clarification (FE)', 'Change requests'])),
    ('platform-foundation', ('Platform foundation', None, ['Build', 'Tenancy', 'Identity', 'Schema', 'Architecture'])),
])
NFR = collections.OrderedDict([
    ('performance', ('Performance and operability', ['Performance'])),
    ('accessibility-and-ux', ('Accessibility and cross-cutting UX', ['Cross-cutting (FE)'])),
])

rows = []
for line in open(REGISTER, encoding='utf-8'):
    if not line.startswith('| VYB-'):
        continue
    parts = line.rstrip('\n').strip('|').strip().split(' | ')
    parts = [p.strip() for p in parts]
    rows.append(dict(id=parts[0], phase=parts[1], cap=parts[2], text=' | '.join(parts[3:-2]),
                     status=parts[-2], session=parts[-1]))

# ---- automated tests that name a requirement ----
tests = collections.defaultdict(list)
pat_be = re.compile(r'void\s+(VYB(\d{4}[a-z]?)_(AC\d+)_(\w+))\s*\(')
pat_fe = re.compile(r"it\(\s*'(VYB(\d{4}[a-z]?)_(AC\d+)_(\w+))'")
for base, pat, kind in ((os.path.join(ROOT, 'backend'), pat_be, 'backend'), (os.path.join(ROOT, 'frontend', 'src'), pat_fe, 'frontend')):
    for d, dirs, files in os.walk(base):
        dirs[:] = [x for x in dirs if x not in ('node_modules', 'target', 'dist')]
        for f in files:
            if not (f.endswith('.java') or f.endswith('.ts') or f.endswith('.tsx')):
                continue
            path = os.path.join(d, f)
            text = open(path, encoding='utf-8', errors='replace').read()
            for m in pat.finditer(text):
                tests['VYB-' + m.group(2)].append((m.group(3), m.group(4), os.path.relpath(path, ROOT).replace(os.sep, '/')))

cap_to_feature = {}
for slug, (_, _, caps) in FEATURES.items():
    for c in caps:
        cap_to_feature[c] = ('F', slug)
for slug, (_, caps) in NFR.items():
    for c in caps:
        cap_to_feature[c] = ('N', slug)

files = {}


def esc(s):
    return s.replace('|', '\\|')


def table(rs, with_tests=True):
    out = ['| ID | Phase | Capability | Requirement | Status | Session |' + (' Tests |' if with_tests else ''),
           '|---|---|---|---|---|---|' + ('---|' if with_tests else '')]
    for r in rs:
        t = ''
        if with_tests:
            n = len(tests.get(r['id'], []))
            t = f" {n} |" if n else ' — |'
        out.append(f"| {r['id']} | {r['phase']} | {esc(r['cap'])} | {esc(r['text'])} | {esc(r['status'])} | {esc(r['session'])} |{t}")
    return out


def counts(rs):
    c = collections.Counter(re.split(r'[ :(]', r['status'])[0] for r in rs)
    return ', '.join(f'{k} {v}' for k, v in sorted(c.items()))


HDR = '<!-- GENERATED by scripts/generate-requirements-docs.py from BUILD-REGISTER.md. Do not edit by hand: change the register, run the script. -->\n'

built = [r for r in rows if r['phase'] != '6']
planned = [r for r in rows if r['phase'] == '6']
unknown = sorted({r['cap'] for r in built if r['cap'] not in cap_to_feature})
if unknown:
    sys.exit('Capabilities with no feature folder (add them to FEATURES or NFR): ' + ', '.join(unknown))

for slug, (title, sec, caps) in FEATURES.items():
    rs = [r for r in built if r['cap'] in caps]
    if not rs:
        continue
    out = [HDR, f'# {title}: requirements', '',
           f'Requirements for **{title}**, from `BUILD-REGISTER.md` (phases 0 to 5, everything built so far). Status: {counts(rs)}.', '']
    if sec:
        out += [f'The screen or topic specification (spec §{sec}) is [`ui-requirements.md`](ui-requirements.md)' if os.path.exists(os.path.join(ROOT, 'docs/02-requirements/FRD', slug, 'ui-requirements.md')) else f'Specification: spec §{sec}, see [`SPECIFICATION-INDEX.md`](../../SPECIFICATION-INDEX.md).', '']
    out += ['`Tests` is the number of automated tests named for the requirement (`VYBnnnn_ACn_...`); they are listed in [`test-cases/automated-tests-index.md`](../../../../test-cases/automated-tests-index.md). '
            '`SUPERSEDED` rows are kept for the record and are not in force.', '']
    out += table(rs)
    files[f'docs/02-requirements/FRD/{slug}/requirement.md'] = '\n'.join(out) + '\n'

for slug, (title, caps) in NFR.items():
    rs = [r for r in built if r['cap'] in caps]
    out = [HDR, f'# Non-functional requirements: {title}', '', f'From `BUILD-REGISTER.md`. Status: {counts(rs)}.', '']
    out += table(rs)
    files[f'docs/02-requirements/non-functional-requirements/{slug}.md'] = '\n'.join(out) + '\n'

# ---- planned (Phase 6) ----
by_cap = collections.OrderedDict()
for r in planned:
    by_cap.setdefault((r['session'], r['cap']), []).append(r)
out = [HDR, '# Planned requirements: Phase 6', '',
       'The next plan, from `BUILD-REGISTER.md` rows VYB-0900 to VYB-0959: hardening first (Sprint 1), then the connector framework and Agile Planner, '
       'manual test execution, releases and defects, Macro Planner sync, AI governance, traceability depth, compliance evidence, configurability and boards, '
       'and pilot readiness. Sizes (S, M, L) and finding IDs (F01 to F42, from the SWLCA gap analysis) are in each row. '
       f'Status: {counts(planned)}.', '',
       'Open decisions that gate some of these: D24 (delivery tool), D25 (configurability and multi-tenancy), D26 (review rounds), D27 (compliance framework), in [`docs/DECISIONS.md`](../../DECISIONS.md).', '']
for (sess, cap), rs in by_cap.items():
    out += [f'## {sess}: {cap}', ''] + table(rs, with_tests=False) + ['']
files['docs/02-requirements/functional-requirements/phase-6-planned.md'] = '\n'.join(out).rstrip('\n') + '\n'

# ---- index of everything ----
out = [HDR, '# Requirements index', '',
       'Every requirement in the register, by feature. `BUILD-REGISTER.md` is the source; these pages are views of it.', '',
       '| Feature | Requirements | Status |', '|---|---|---|']
for slug, (title, sec, caps) in FEATURES.items():
    rs = [r for r in built if r['cap'] in caps]
    if rs:
        out.append(f'| [{title}](FRD/{slug}/requirement.md) | {len(rs)} | {counts(rs)} |')
for slug, (title, caps) in NFR.items():
    rs = [r for r in built if r['cap'] in caps]
    out.append(f'| [Non-functional: {title}](non-functional-requirements/{slug}.md) | {len(rs)} | {counts(rs)} |')
out.append(f'| [Planned: Phase 6](functional-requirements/phase-6-planned.md) | {len(planned)} | {counts(planned)} |')
out += ['', f'Total: {len(rows)} requirements ({len(built)} in phases 0 to 5, {len(planned)} planned in phase 6).', '',
        'See also: [`SPECIFICATION-INDEX.md`](SPECIFICATION-INDEX.md) (the build specification, by section), '
        '[`specification-guide.md`](specification-guide.md), [`docs/DECISIONS.md`](../DECISIONS.md).']
files['docs/02-requirements/README.md'] = '\n'.join(out) + '\n'

# ---- automated tests index ----
out = [HDR, '# Automated tests, by requirement', '',
       'Every automated test whose name starts with a requirement id (`VYBnnnn_ACn_shortDescription`), grouped by requirement. '
       'These are the executable test cases; the manual and UAT cases are in the other folders here. '
       f'{sum(len(v) for v in tests.values())} tests cover {len(tests)} requirements.', '']
titles = {r['id']: r for r in rows}
for rid in sorted(tests):
    r = titles.get(rid)
    out.append(f"## {rid}" + (f": {r['text']}" if r else ' (not in the register)'))
    out.append('')
    for ac, name, path in sorted(tests[rid]):
        out.append(f'- `{ac}` {name} (`{path}`)')
    out.append('')
files['test-cases/automated-tests-index.md'] = '\n'.join(out).rstrip('\n') + '\n'

changed = []
for rel, content in files.items():
    path = os.path.join(ROOT, rel)
    old = open(path, encoding='utf-8').read() if os.path.exists(path) else None
    if old != content:
        changed.append(rel)
        if '--check' not in sys.argv:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, 'w', encoding='utf-8').write(content)
if '--check' in sys.argv:
    if changed:
        print('Out of date (run scripts/generate-requirements-docs.py):\n  ' + '\n  '.join(changed))
        sys.exit(1)
    print('Requirement docs are up to date.')
else:
    print(f'{len(rows)} requirements, {sum(len(v) for v in tests.values())} tests; wrote {len(changed)} file(s).')
