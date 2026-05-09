#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 8;

use Archive::Zip;

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
