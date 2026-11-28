"""Turn the NCVEC question pool (pdftotext output) into question + correct answer only.

The pool lists four choices per question, three of them wrong. A library passage must not carry the
wrong choices as if they were facts, so only the keyed answer is kept. Answers that refer to the
other choices ("All these choices are correct", "Both A and B") are expanded to those choices.
usage: python -I ncvec2md.py ncvec.txt out.md
"""
import re
import sys

src, out = sys.argv[1:3]
t = open(src, encoding='utf-8').read()
t = re.sub(r'\s+', ' ', t)
start = t.find('T1A01 (')
body = t[start:]
qre = re.compile(r'(T\d[A-Z]\d{2}) \(([A-D])\) ?(?:\[([^\]]*)\] )?(.*?) ~~')
sec = re.compile(r'(T\d[A-Z]) ([A-Z][^~]*?)(?= T\d[A-Z]\d{2} \()')
items, problems = [], []
for m in qre.finditer(body):
    qid, key, ref, rest = m.groups()
    p = re.match(r'(.*?) A\. (.*?) B\. (.*?) C\. (.*?) D\. (.*)$', rest)
    if not p:
        problems.append(qid)
        continue
    q, a, b, c, d = (x.strip() for x in p.groups())
    ch = {'A': a, 'B': b, 'C': c, 'D': d}
    ans = ch[key]
    low = ans.lower()
    if low.startswith('all these choices are correct') or low.startswith('all of these choices are correct'):
        others = [ch[k] for k in 'ABCD' if k != key]
        ans = 'All of these are correct: ' + '; '.join(others) + '.'
    elif re.match(r'(both )?([a-d]) and ([a-d])( are correct)?', low):
        mm = re.match(r'(?:both )?([a-d]) and ([a-d])', low)
        ans = ans + ': ' + '; '.join(ch[x.upper()] for x in mm.groups()) + '.'
    elif 'choices' in low or re.search(r'\b[A-D] and [A-D]\b', ans):
        problems.append(qid + ' refers to other choices: ' + ans)
    items.append((qid, ref, q, ans))

heads = {}
for m in re.finditer(r'(T\d[A-Z]) ([A-Z][a-z][^~]{3,300}?)(?= T\d[A-Z]01 ?\()', body):
    heads[m.group(1)] = m.group(2).strip()

lines = ["# FCC amateur radio Technician exam: questions with their correct answers (2026–2030 pool)", "",
         "Source: NCVEC Question Pool Committee, 2026-2030 Technician Class (Element 2) Question Pool, public release "
         "with errata of 2026-02-19 (https://ncvec.org/index.php/2026-2030-technician-question-pool), in effect "
         "2026-07-01 to 2030-06-30. Released by NCVEC into the public domain. Only each question's keyed correct "
         "answer is kept (the three wrong choices are left out so they cannot be read as facts); answers that "
         "refer to other choices are written out. Figures T-1, T-2 and T-3 are in the separate diagrams PDF. "
         "The bracketed reference is the FCC rule (47 CFR) the question tests, where given.", ""]
cur = None
for qid, ref, q, ans in items:
    g = qid[:3]
    if g != cur:
        cur = g
        lines += [f"## {g} {heads.get(g, '')}".rstrip(), ""]
    lines += [f"**{qid}**{' [' + ref + ']' if ref else ''} {q}", f"Answer: {ans}", ""]
open(out, 'w', encoding='utf-8', newline='\n').write("\n".join(lines))
print(len(items), 'questions;', len(problems), 'problems')
for p in problems:
    print('  ', p)
