#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 13;
use File::Path qw(rmtree);

use Archive::Zip;

my $tmp = "archive_zip_compat_$$";
END { rmtree($tmp) if -d $tmp; }
mkdir $tmp or die "mkdir $tmp: $!";

my $zip = Archive::Zip->new();
$zip->addString('{}', 'Fruit-Role-Fermentable-1.0/META.json');
$zip->addString('package Fruit::Role::Fermentable;', 'Fruit-Role-Fermentable-1.0/lib/Fruit/Role/Fermentable.pm');
$zip->addString('notes', 'README');

my $meta_re = qr/^([^\/]+\/)?META\.(json|yml)/;
my @meta = $zip->membersMatching($meta_re);
is(scalar(@meta), 1, 'membersMatching accepts qr// regex');
is($meta[0]->fileName, 'Fruit-Role-Fermentable-1.0/META.json', 'qr// regex matched META file');
is(scalar($zip->membersMatching($meta_re)), 1, 'membersMatching returns count in scalar context');
is($zip->contents($meta[0]), '{}', 'zip contents returns member content in scalar context');
is_deeply([$zip->contents($meta[0])], ['{}', 0], 'zip contents returns content and status in list context');

my @meta_hash = $zip->membersMatching({ regex => $meta_re });
is(scalar(@meta_hash), 1, 'membersMatching accepts hash regex form');

my @modules = $zip->membersMatching(qr/\.pm\z/);
is($modules[0]->fileName, 'Fruit-Role-Fermentable-1.0/lib/Fruit/Role/Fermentable.pm', 'membersMatching uses Perl regex semantics');

my @plain = $zip->membersMatching('README');
is($plain[0]->fileName, 'README', 'membersMatching still accepts string patterns');

my $zip_path = "$tmp/read.zip";
is($zip->writeToFileNamed($zip_path), 0, 'wrote zip fixture');

my $read = Archive::Zip->new();
is($read->read($zip_path), 0, 'read zip fixture');

my ($read_meta) = $read->membersMatching(qr/META\.json\z/);
is($read_meta->{fileName}, 'Fruit-Role-Fermentable-1.0/META.json', 'member hash exposes fileName compatibility field');
is($read->extractMember($read_meta, "$tmp/meta.json"), 0, 'extractMember accepts member object');
ok(-e "$tmp/meta.json", 'member object extraction wrote file');
