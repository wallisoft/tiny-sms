#!/bin/bash
# Convert favicon to Android mipmap icons
# Run from TinySMS project root
# Requires ImageMagick: sudo apt install imagemagick

SOURCE="favicon-512.png"  # Your green T favicon at 512x512

mkdir -p app/src/main/res/mipmap-mdpi
mkdir -p app/src/main/res/mipmap-hdpi
mkdir -p app/src/main/res/mipmap-xhdpi
mkdir -p app/src/main/res/mipmap-xxhdpi
mkdir -p app/src/main/res/mipmap-xxxhdpi

convert $SOURCE -resize 48x48   app/src/main/res/mipmap-mdpi/ic_launcher.png
convert $SOURCE -resize 72x72   app/src/main/res/mipmap-hdpi/ic_launcher.png
convert $SOURCE -resize 96x96   app/src/main/res/mipmap-xhdpi/ic_launcher.png
convert $SOURCE -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher.png
convert $SOURCE -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

# Round icons (same but circular crop)
for size in 48 72 96 144 192; do
    dir="mipmap-$(echo $size | awk '{
        if($1==48)  print "mdpi"
        if($1==72)  print "hdpi"
        if($1==96)  print "xhdpi"
        if($1==144) print "xxhdpi"
        if($1==192) print "xxxhdpi"
    }')"
    convert $SOURCE -resize ${size}x${size} \
        \( +clone -alpha extract \
           -draw "fill black polygon 0,0 0,${size} ${size},0 fill white circle $((size/2)),$((size/2)) $((size/2)),0" \
           \( +clone -flip \) -compose Multiply -composite \
           \( +clone -flop \) -compose Multiply -composite \
        \) -alpha off -compose CopyOpacity -composite \
        app/src/main/res/$dir/ic_launcher_round.png
done

echo "Icons generated!"
echo "Also copy favicon-512.png to Play Store listing as the 512x512 icon"
