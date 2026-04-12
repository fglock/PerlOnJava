#!/usr/bin/perl
# Test error handling: missing files, bad operations, edge cases.
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

plan tests => 10;

use_ok('Image::Magick');

my $tmpdir = tempdir(CLEANUP => 1);

# --- Read nonexistent file returns error ---
{
    my $img = Image::Magick->new;
    my $err = $img->Read('/nonexistent/path/image.png');
    ok($err, 'Read nonexistent file returns error');
    like("$err", qr/unable to open|No such file/i, 'Error message mentions file not found');
}

# --- Write without reading returns error ---
{
    my $img = Image::Magick->new;
    my $err = $img->Write("$tmpdir/empty.png");
    ok($err, 'Write with no image returns error');
}

# --- Read with no arguments returns error ---
{
    my $img = Image::Magick->new;
    my $err = $img->Read();
    ok($err, 'Read with no arguments returns error');
}

# --- Get on empty image returns undef ---
{
    my $img = Image::Magick->new;
    my $w = $img->Get('width');
    ok(!defined $w, 'Get width on empty image returns undef');
}

# --- Unsupported method dies ---
{
    my $img = Image::Magick->new;
    $img->Set(size => '50x50');
    $img->Read('xc:white');
    eval { $img->Display() };
    like($@, qr/not supported/i, 'Display() dies with "not supported"');
}

# --- Undefined method dies ---
{
    my $img = Image::Magick->new;
    eval { $img->CompletelyBogusMethod() };
    ok($@, 'Undefined method dies');
}

# --- Constants are correct values ---
{
    is(Image::Magick::Success(), 0, 'Success is 0');
    ok(Image::Magick::ErrorException() > 0, 'ErrorException is positive');
}

done_testing();
