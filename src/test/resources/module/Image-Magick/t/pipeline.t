#!/usr/bin/perl
# Test deferred execution pipeline and multi-operation chains.
# Verifies that queued operations are flushed correctly.
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

plan tests => 16;

use_ok('Image::Magick');

my $tmpdir = tempdir(CLEANUP => 1);

# --- Chain: Resize then Rotate ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '200x100');
    $img->Read('xc:red');
    $img->Resize(geometry => '100x50');
    $img->Rotate(degrees => 90);
    $img->Write("$tmpdir/chain1.png");

    my $check = Image::Magick->new;
    $check->Read("$tmpdir/chain1.png");
    # 100x50 rotated 90 = 50x100
    is($check->Get('width'), 50, 'Chain resize+rotate: width is 50');
    is($check->Get('height'), 100, 'Chain resize+rotate: height is 100');
}

# --- Chain: Resize then Flip then Flop ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '150x150');
    $img->Read('xc:blue');
    $img->Resize(geometry => '75x75');
    $img->Flip();
    $img->Flop();
    $img->Write("$tmpdir/chain2.png");

    my $check = Image::Magick->new;
    $check->Read("$tmpdir/chain2.png");
    is($check->Get('width'), 75, 'Chain resize+flip+flop: width is 75');
    is($check->Get('height'), 75, 'Chain resize+flip+flop: height is 75');
}

# --- Get triggers flush ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '300x200');
    $img->Read('xc:green');
    $img->Resize(geometry => '150x100');
    # Get should flush pending resize before querying
    my $w = $img->Get('width');
    my $h = $img->Get('height');
    is($w, 150, 'Get flushes pending ops: width is 150');
    is($h, 100, 'Get flushes pending ops: height is 100');
}

# --- Clone preserves state ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '100x100');
    $img->Read('xc:white');
    $img->Resize(geometry => '50x50');
    $img->Write("$tmpdir/pre_clone.png");

    my $clone = $img->Clone;
    $clone->Resize(geometry => '25x25');
    $clone->Write("$tmpdir/clone_out.png");

    # Original should still be 50x50
    my $orig_check = Image::Magick->new;
    $orig_check->Read("$tmpdir/pre_clone.png");
    is($orig_check->Get('width'), 50, 'Clone: original unchanged at 50');

    # Clone should be 25x25
    my $clone_check = Image::Magick->new;
    $clone_check->Read("$tmpdir/clone_out.png");
    is($clone_check->Get('width'), 25, 'Clone: clone resized to 25');
}

# --- Write to different formats ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '80x80');
    $img->Read('xc:red');

    $img->Write("$tmpdir/out.jpg");
    ok(-s "$tmpdir/out.jpg" > 0, 'Write JPEG: non-empty');

    $img->Write("$tmpdir/out.gif");
    ok(-s "$tmpdir/out.gif" > 0, 'Write GIF: non-empty');

    $img->Write("$tmpdir/out.bmp");
    ok(-s "$tmpdir/out.bmp" > 0, 'Write BMP: non-empty');

    $img->Write("$tmpdir/out.png");
    ok(-s "$tmpdir/out.png" > 0, 'Write PNG: non-empty');
}

# --- Blob round-trip preserves dimensions ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '64x64');
    $img->Read('xc:blue');
    $img->Resize(geometry => '32x32');

    my @blob = $img->ImageToBlob(magick => 'PNG');
    ok(length($blob[0]) > 0, 'Blob round-trip: blob is non-empty');

    my $img2 = Image::Magick->new;
    $img2->BlobToImage($blob[0]);
    is($img2->Get('width'), 32, 'Blob round-trip: width preserved at 32');
    is($img2->Get('height'), 32, 'Blob round-trip: height preserved at 32');
}

done_testing();
