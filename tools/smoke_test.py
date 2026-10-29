#!/usr/bin/env python3
"""speed_tracer_pc.html の動作確認(自動更新の前に必ず通す)。

使い方: python3 tools/smoke_test.py [speed_tracer_pc.html]
  - 全モードを開いてJSエラーが出ないこと
  - オープニング(https扱い)が最後まで再生されて消えること
  - サーキットのデモ走行・アクセル0点合わせ・要望送信ボタンが動くこと
  - ファーム埋め込み用ヘッダーが作れること
を確かめ、問題があれば終了コード1を返す。Playwright と Chromium が必要。
"""
import os
import subprocess
import sys
import tempfile

from playwright.sync_api import sync_playwright

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', 'speed_tracer_pc.html')
SRC = os.path.abspath(SRC)
EXE = '/opt/pw-browsers/chromium' if os.path.exists('/opt/pw-browsers/chromium') else None
fails = []


def check(cond, msg):
    print(('OK   ' if cond else 'FAIL ') + msg)
    if not cond:
        fails.append(msg)


html = open(SRC, encoding='utf-8').read()
with sync_playwright() as p:
    b = p.chromium.launch(executable_path=EXE) if EXE else p.chromium.launch()
    for vp in ({'width': 412, 'height': 915}, {'width': 915, 'height': 412}):
        ctx = b.new_context(viewport=vp)
        ctx.route('https://pages.test/**', lambda r: r.fulfill(status=200, content_type='text/html; charset=utf-8', body=html)
                  if r.request.url.split('?')[0].endswith('.html') else r.fulfill(status=404, body=''))
        posts = []
        ctx.route('https://docs.google.com/forms/**', lambda r: (posts.append(r.request.post_data or ''), r.fulfill(status=200, body='ok')))
        pg = ctx.new_page()
        errs = []
        pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.goto('https://pages.test/speed_tracer_pc.html?op=on')
        pg.wait_for_timeout(1500)
        if pg.evaluate("!!document.getElementById('stOp')"):  # 期限(OP_UNTIL)を過ぎると出ないのが正しい
            pg.wait_for_timeout(6500)
            check(pg.evaluate("!document.getElementById('stOp')"), f'{vp}: オープニングが終わって消える')
        else:
            print(f'---- {vp}: オープニングは表示されない(期限切れ、または無効)')
        modes = pg.evaluate("[...document.querySelectorAll('.modeBtn')].map(b=>b.dataset.mode)")
        check(len(modes) >= 5, f'{vp}: モードボタンがある ({modes})')
        for m in modes:
            pg.click(f'.modeBtn[data-mode="{m}"]')
            pg.wait_for_timeout(250)
            vis = pg.evaluate("[...document.querySelectorAll('.mode')].filter(s=>getComputedStyle(s).display!=='none').map(s=>s.dataset.mode)")
            check(vis == [m], f'{vp}: {m} モードだけが表示される')
        pg.click('.modeBtn[data-mode="circuit"]')
        pg.click('#cirDemoBtn')
        pg.wait_for_timeout(28000)
        laps = pg.evaluate("window.__circuitTest.state().store.tracks[window.__circuitTest.state().store.cur].laps.length")
        check(laps >= 1, f'{vp}: サーキットのデモでラップが記録される ({laps})')
        pg.click('#cirDemoBtn')
        pg.click('.modeBtn[data-mode="setting"]')
        pg.fill('#fbText', 'テストの要望です')
        pg.click('#fbSendBtn')
        pg.wait_for_timeout(800)
        check('送信しました' in pg.inner_text('#fbInfo') and any('entry.' in x for x in posts), f'{vp}: 要望がフォームへ送られる')
        check(not errs, f'{vp}: JSエラーなし {errs[:3]}')
        ctx.close()
    b.close()

with tempfile.TemporaryDirectory() as d:
    r = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), 'make_webpage_header.py'), SRC, os.path.join(d, 'w.h')],
                       capture_output=True, text=True)
    check(r.returncode == 0, 'ファーム埋め込み用ヘッダーが作れる')

print('\n結果:', '全部OK' if not fails else f'{len(fails)} 件失敗')
sys.exit(1 if fails else 0)
