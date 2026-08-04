#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);

my $dir = tempdir(CLEANUP => 1);
my $module_dir = File::Spec->catdir($dir, 'RequireFalse');
my $module_file = File::Spec->catfile($module_dir, 'NoTrueValue.pm');
make_path($module_dir);

open my $fh, '>', $module_file or die "open $module_file: $!";
print {$fh} "package RequireFalse::NoTrueValue;\n";
close $fh or die "close $module_file: $!";

local @INC = ($dir, @INC);
my $inc_key = 'RequireFalse/NoTrueValue.pm';

for my $attempt (1 .. 2) {
    my $loaded = eval { require RequireFalse::NoTrueValue; 1 };
    ok(!$loaded, "false module result rejects require attempt $attempt");
    like(
        $@,
        qr/\Q$inc_key\E did not return a true value/,
        "require attempt $attempt reports the false module result",
    );
    ok(!exists $INC{$inc_key}, "failed require attempt $attempt leaves no INC entry");
}

done_testing();
