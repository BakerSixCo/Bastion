"""Fetch NCCIH Herbs at a Glance fact sheets and the TCM/acupuncture/supplement-safety pages into one Markdown file.

usage: python -I nccih.py nccih-index.html out.md
NIH/NCCIH pages are U.S. government works (public domain). Page text is kept; menus and footers removed.
"""
import html
import re
import sys
import time
import urllib.request

index, out = sys.argv[1:3]
NOT_HERBS = {'atoz', 'complementary-alternative-or-integrative-health-whats-in-a-name', 'espanol', 'herblist-app',
             'herbsataglance', 'know-science', 'pain', 'providers', 'safety', 'tips'}
with open(index, encoding='utf-8') as source:
    herbs = sorted(set(re.findall(r'href="/health/([a-z0-9-]+)"', source.read())) - NOT_HERBS)
extra = ['traditional-chinese-medicine-what-you-need-to-know', 'acupuncture-what-you-need-to-know',
         'using-dietary-supplements-wisely', 'safety']
UA = {'User-Agent': 'Mozilla/5.0 (offline-library-builder)'}


def fetch(slug):
    req = urllib.request.Request('https://www.nccih.nih.gov/health/' + slug, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode('utf-8', 'replace')


def body_text(page):
    m = re.search(r'<main\b.*?>(.*?)</main>', page, re.S) or re.search(r'<article\b.*?>(.*?)</article>', page, re.S)
    s = m.group(1) if m else page
    s = re.sub(r'<(script|style|nav|aside|footer|form)\b.*?</\1>', ' ', s, flags=re.S)
    s = re.sub(r'<h([1-6])[^>]*>(.*?)</h\1>', lambda mm: '\n\n' + '#' * (int(mm.group(1)) + 2) + ' ' + re.sub('<[^>]+>', '', mm.group(2)).strip() + '\n\n', s, flags=re.S)
    s = re.sub(r'<li[^>]*>', '\n- ', s)
    s = re.sub(r'<br\s*/?>|</p>|</li>|</div>|</tr>', '\n', s)
    s = re.sub(r'</t[dh]>', ' | ', s)
    s = html.unescape(re.sub(r'<[^>]+>', '', s))
    s = re.sub(r'[ \t\xa0]+', ' ', s)
    s = re.sub(r'\n[ \t]+', '\n', s)
    s = re.sub(r'\n{3,}', '\n\n', s).strip()
    # drop share widgets and page furniture that survive
    s = re.sub(r'(?m)^(Share|Print|Email|Facebook|X|Twitter|LinkedIn|Pinterest|On this page)\s*$', '', s)
    return re.sub(r'\n{3,}', '\n\n', s).strip()


parts, failed = [], []
for slug in extra + herbs:
    try:
        page = fetch(slug)
        t = re.search(r'<title>(.*?)</title>', page, re.S)
        title = html.unescape(t.group(1)).split('|')[0].strip() if t else slug
        text = body_text(page)
        h1 = re.search(r'(?m)^### (.+)$', text)
        if h1:
            title = h1.group(1).strip()
            text = text[:h1.start()] + text[h1.end():]
        parts.append(f"## {title}\n\nSource page: https://www.nccih.nih.gov/health/{slug}\n\n{text}\n")
    except Exception as e:  # keep going; report at the end
        failed.append(f"{slug}: {e}")
    time.sleep(0.5)

head = ("# NIH NCCIH: Traditional Chinese Medicine, acupuncture, supplement safety and Herbs at a Glance\n\n"
        "Source: National Center for Complementary and Integrative Health (NCCIH), National Institutes of Health "
        "(https://www.nccih.nih.gov/health/), pages downloaded 2026-11-26. U.S. government work (public domain). "
        "Herbs at a Glance fact sheets give each herb's common names, what the science says, safety, side effects and "
        "cautions. Page text kept; site menus, share buttons and footers removed.\n\n")
with open(out, 'w', encoding='utf-8', newline='\n') as target:
    target.write(head + "\n".join(parts))
print(len(parts), 'pages;', len(failed), 'failed')
for f in failed:
    print('  FAILED', f)
