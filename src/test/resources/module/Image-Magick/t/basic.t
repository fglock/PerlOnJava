#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir tempfile);

# Check if ImageMagick CLI is available
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

plan tests => 30;

# --- Module loading ---
use_ok('Image::Magick');

# --- Constants ---
is(Image::Magick::Success(), 0, 'Success constant is 0');
is(Image::Magick::ErrorException(), 400, 'ErrorException constant is 400');
is(Image::Magick::QuantumDepth(), 16, 'QuantumDepth constant is 16');
ok(Image::Magick::MaxRGB() > 0, 'MaxRGB is positive');

# --- Constructor ---
my $img = Image::Magick->new;
isa_ok($img, 'Image::Magick');
is(ref $img, 'Image::Magick', 'Object is blessed arrayref');
is(scalar @$img, 0, 'New object has no images');

# --- Set/Get static attributes ---
$img->Set(size => '100x100');
is($img->Get('version'), "PerlOnJava Image::Magick $Image::Magick::VERSION (CLI wrapper)",
   'Get version returns wrapper version string');

# --- Create test image using pseudo-image ---
my $tmpdir = tempdir(CLEANUP => 1);

my $img2 = Image::Magick->new;
$img2->Set(size => '100x100');
my $err = $img2->Read('xc:red');
ok(!$err, 'Read pseudo-image xc:red succeeds') or diag("Read error: $err");
is(scalar @$img2, 1, 'One image in sequence after Read');

# --- Get image attributes ---
SKIP: {
    skip "No image loaded", 4 unless @$img2;

    my $w = $img2->Get('width');
    my $h = $img2->Get('height');
    ok(defined $w && $w == 100, "Width is 100 (got: " . ($w // 'undef') . ")");
    ok(defined $h && $h == 100, "Height is 100 (got: " . ($h // 'undef') . ")");

    my $fmt = $img2->Get('magick');
    ok(defined $fmt, "Format is defined (got: " . ($fmt // 'undef') . ")");

    my $depth = $img2->Get('depth');
    ok(defined $depth && $depth > 0, "Depth is positive (got: " . ($depth // 'undef') . ")");
}

# --- Write ---
{
    my $outfile = "$tmpdir/test_write.png";
    my $err = $img2->Write($outfile);
    ok(!$err, 'Write to PNG succeeds') or diag("Write error: $err");
    ok(-e $outfile, 'Output file exists');
    ok(-s $outfile > 0, 'Output file is non-empty');
}

# --- Read written file back ---
{
    my $img3 = Image::Magick->new;
    my $err = $img3->Read("$tmpdir/test_write.png");
    ok(!$err, 'Read PNG file succeeds') or diag("Read error: $err");
    is(scalar @$img3, 1, 'One image after reading PNG');
}

# --- Ping ---
{
    my @info = $img2->Ping("$tmpdir/test_write.png");
    ok(@info >= 2, 'Ping returns at least width and height');
    is($info[0], 100, 'Ping width is 100');
    is($info[1], 100, 'Ping height is 100');
}

# --- Clone ---
{
    my $clone = $img2->Clone;
    isa_ok($clone, 'Image::Magick');
    is(scalar @$clone, 1, 'Clone has one image');
}

# --- Image manipulation (Resize) ---
{
    my $img4 = Image::Magick->new;
    $img4->Set(size => '200x200');
    $img4->Read('xc:blue');
    $img4->Resize(geometry => '50x50');
    my $out = "$tmpdir/resized.png";
    $img4->Write($out);
    ok(-e $out, 'Resized image written');

    my $check = Image::Magick->new;
    $check->Read($out);
    my $w = $check->Get('width');
    # Width should be 50 after resize
    ok(defined $w && $w == 50, "Resized width is 50 (got: " . ($w // 'undef') . ")");
}

# --- ImageToBlob / BlobToImage ---
{
    my @blob = $img2->ImageToBlob(magick => 'PNG');
    ok(@blob > 0, 'ImageToBlob returns data');
    ok(length($blob[0]) > 0, 'Blob is non-empty');

    my $img5 = Image::Magick->new;
    my $err = $img5->BlobToImage($blob[0]);
    ok(!$err, 'BlobToImage succeeds') or diag("BlobToImage error: $err");
}

done_testing();
