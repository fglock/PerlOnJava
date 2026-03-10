#!/usr/bin/env perl
# Example: Using Image::ExifTool module for batch processing
#
# Prerequisites:
#   Download and extract ExifTool in the PerlOnJava root directory:
#     cd PerlOnJava
#     curl -LO https://exiftool.org/Image-ExifTool-13.44.tar.gz
#     tar xzf Image-ExifTool-13.44.tar.gz
#
# Run with:
#   ./gradlew run --args='examples/ExifToolExample.pl'

use strict;
use warnings;
use lib 'Image-ExifTool-13.44/lib';
use Image::ExifTool;

# Create one ExifTool instance - reusable for multiple files
my $exif = Image::ExifTool->new();

# Sample images to process
my @images = (
    'Image-ExifTool-13.44/t/images/Canon.jpg',
    'Image-ExifTool-13.44/t/images/Nikon.jpg',
    'Image-ExifTool-13.44/t/images/Sony.jpg',
);

print "ExifTool version: ", $exif->GetValue('ExifToolVersion'), "\n\n";

for my $file (@images) {
    my $info = $exif->ImageInfo($file, qw(Make Model DateTimeOriginal ImageSize));
    
    print "File: $file\n";
    for my $tag (sort keys %$info) {
        print "  $tag: $info->{$tag}\n";
    }
    print "\n";
}
