# Intro title cards

The intro's title cards are recorded from the site's own React Bits components
(VoxelArc, GlyphLottery, CipherReveal) and the LogoMark.

1. Copy `page.tsx.txt` to `app/intro-cards/page.tsx` (do not commit it: it would be a public route).
2. `pnpm install && pnpm exec next dev -p 3123`
3. Record frame by frame with virtual time (needs Playwright + Chromium):
   `node menu-mod/intro/cards/capture.js 1 11.05 out1` and `node menu-mod/intro/cards/capture.js 2 9.92 out2`
4. `ffmpeg -framerate 24 -i out1/f%04d.png -c:v libx264 -crf 16 -pix_fmt yuv420p card1.mp4` (same for card 2).

Card 1 runs over the quiet start of the track (0 to 11.05 s), card 2 is the end card with the address.
