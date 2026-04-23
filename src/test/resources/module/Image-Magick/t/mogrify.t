#!/usr/bin/perl
# Test image manipulation operations (Mogrify dispatch)
# Verifies operations produce measurable changes in image attributes.
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);

my $has_magick = 0;
for my $cmd (qw(magick convert)) {
    my $out = `$cmd --version 2>&1`;
    if ($? == 0 && $out =~ /ImageMagick/) {
        $has_magick = 1;
        last;
    }
}
unless ($has_magick) {
    plan skip_all => 'ImageMagick CLI tools not installed';
}

plan tests => 21;

use_ok('Image::Magick');

my $tmpdir = tempdir(CLEANUP => 1);

# Helper: create a test image of given size
sub make_image {
    my ($w, $h, $color) = @_;
    $color //= 'white';
    my $img = Image::Magick->new;
    $img->Set(size => "${w}x${h}");
    $img->Read("xc:$color");
    return $img;
}

# --- Resize ---
{
    my $img = make_image(200, 200);
    $img->Resize(geometry => '100x100');
    $img->Write("$tmpdir/resized.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/resized.png");
    is($check->Get('width'), 100, 'Resize: width is 100');
    is($check->Get('height'), 100, 'Resize: height is 100');
}

# --- Crop ---
{
    my $img = make_image(200, 200, 'blue');
    $img->Crop(geometry => '50x50+10+10');
    $img->Write("$tmpdir/cropped.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/cropped.png");
    my $w = $check->Get('width');
    my $h = $check->Get('height');
    ok($w == 50, "Crop: width is 50 (got $w)");
    ok($h == 50, "Crop: height is 50 (got $h)");
}

# --- Rotate ---
{
    my $img = make_image(100, 50, 'red');
    $img->Rotate(degrees => 90);
    $img->Write("$tmpdir/rotated.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/rotated.png");
    my $w = $check->Get('width');
    my $h = $check->Get('height');
    # After 90-degree rotation, width and height should swap
    ok($w == 50, "Rotate 90: width is 50 (got $w)");
    ok($h == 100, "Rotate 90: height is 100 (got $h)");
}

# --- Flip (vertical mirror) ---
{
    my $img = make_image(100, 100, 'green');
    my $err = $img->Flip();
    ok(!$err, 'Flip: no error') or diag("Flip error: $err");
    $img->Write("$tmpdir/flipped.png");
    ok(-s "$tmpdir/flipped.png" > 0, 'Flip: output file non-empty');
}

# --- Flop (horizontal mirror) ---
{
    my $img = make_image(100, 100, 'green');
    my $err = $img->Flop();
    ok(!$err, 'Flop: no error') or diag("Flop error: $err");
    $img->Write("$tmpdir/flopped.png");
    ok(-s "$tmpdir/flopped.png" > 0, 'Flop: output file non-empty');
}

# --- Negate ---
{
    my $img = make_image(50, 50, 'black');
    my $err = $img->Negate();
    ok(!$err, 'Negate: no error') or diag("Negate error: $err");
    $img->Write("$tmpdir/negated.png");
    ok(-s "$tmpdir/negated.png" > 0, 'Negate: output file non-empty');
}

# --- Scale ---
{
    my $img = make_image(200, 200);
    $img->Scale(geometry => '75x75');
    $img->Write("$tmpdir/scaled.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/scaled.png");
    is($check->Get('width'), 75, 'Scale: width is 75');
    is($check->Get('height'), 75, 'Scale: height is 75');
}

# --- Thumbnail ---
{
    my $img = make_image(400, 400);
    $img->Thumbnail(geometry => '50x50');
    $img->Write("$tmpdir/thumb.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/thumb.png");
    is($check->Get('width'), 50, 'Thumbnail: width is 50');
    is($check->Get('height'), 50, 'Thumbnail: height is 50');
}

# --- Trim (trim blank borders) ---
{
    # Create 200x200 with content only in center 50x50
    my $img = make_image(200, 200, 'white');
    $img->Draw(fill => 'red', primitive => 'rectangle', points => '75,75 125,125');
    $img->Trim();
    $img->Write("$tmpdir/trimmed.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/trimmed.png");
    my $w = $check->Get('width');
    my $h = $check->Get('height');
    ok($w < 200, "Trim: width reduced (got $w)");
    ok($h < 200, "Trim: height reduced (got $h)");
}

# --- Border ---
{
    my $img = make_image(100, 100, 'blue');
    $img->Border(geometry => '10x10');
    $img->Write("$tmpdir/bordered.png");
    my $check = Image::Magick->new;
    $check->Read("$tmpdir/bordered.png");
    is($check->Get('width'), 120, 'Border: width is 120 (100 + 2*10)');
    is($check->Get('height'), 120, 'Border: height is 120 (100 + 2*10)');
}

done_testing();
