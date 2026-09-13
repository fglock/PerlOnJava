#!/usr/bin/env perl
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Spec;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir(dirname(__FILE__), '..', '..', '..'));
my $makefile = File::Spec->catfile($root, 'Makefile');
my $tester = File::Spec->catfile($root, 'dev', 'tools', 'cpan_random_tester.pl');

open my $mf, '<', $makefile or die "Cannot read $makefile: $!";
my $make_text = do { local $/; <$mf> };
close $mf;
open my $tf, '<', $tester or die "Cannot read $tester: $!";
my $tester_text = do { local $/; <$tf> };
close $tf;

my @modules = qw(PPR Catalyst Mojolicious Image::ExifTool DateTime Template DBIx::Class);
like($make_text, qr/^test-cpan-release-acceptance: build$/m,
    'release acceptance target builds before testing');
like($make_text, qr/--modules "\$\$modules".*--jobs 8 --strict-exit/s,
    'release target uses explicit modules, parallel test jobs, and strict exit');
like($make_text, qr/timeout 28800 perl dev\/tools\/cpan_random_tester\.pl/s,
    'release target has an outer timeout');
for my $module (@modules) {
    like($make_text, qr/\Q$module\E/, "release target includes $module");
}
like($tester_text, qr/'strict-exit'\s*=>\s*\\\$strict_exit/,
    'tester exposes strict exit mode');
like($tester_text, qr/Strict target failures:/,
    'tester reports strict target failures');
like($tester_text, qr/NOT TESTED \(already installed\/up to date\)/,
    'strict mode rejects an up-to-date target that was not tested');

done_testing;
