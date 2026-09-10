import os
try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    os.system("Script/.venv/bin/pip install pillow")
    from PIL import Image, ImageDraw, ImageFont

# 108x108 dp is standard adaptive icon size. Let's do 1080x1080 px for high res.
size = (1080, 1080)
# Foreground should have transparent background
img = Image.new('RGBA', size, (255, 255, 255, 0))
draw = ImageDraw.Draw(img)

# Try to find a bold font
font_path = "/System/Library/Fonts/HelveticaNeue.ttc"
if not os.path.exists(font_path):
    font_path = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"

try:
    font = ImageFont.truetype(font_path, 280, index=1) # Helvetica Neue Bold
except:
    try:
        font = ImageFont.truetype(font_path, 280)
    except:
        font = ImageFont.load_default()

text = "iash"
# Get text bounding box
bbox = draw.textbbox((0, 0), text, font=font)
text_width = bbox[2] - bbox[0]
text_height = bbox[3] - bbox[1]

x = (size[0] - text_width) / 2
# Adjust Y to visually center (lowercase letters like i, a, s, h have specific ascenders/descenders)
y = (size[1] - text_height) / 2 - 40 

# Draw shadow
shadow_offset = 6
draw.text((x + shadow_offset, y + shadow_offset), text, font=font, fill=(0, 0, 0, 80))

# Draw text (White color for contrast, assuming background is dark/gradient, wait, we can just make it dark blue or black)
# Since we don't know the background, let's make the text very distinct.
# The user's current background is probably green or teal.
# Let's draw it in #FFFFFF (White)
draw.text((x, y), text, font=font, fill=(255, 255, 255, 255))

# Also maybe add a small AI symbol or dot
dot_x = x + text_width - 10
dot_y = y + text_height - 20
draw.ellipse([dot_x, dot_y, dot_x+30, dot_y+30], fill=(0, 200, 255, 255))

img.save("app/src/main/res/drawable/ic_launcher_foreground.png")
