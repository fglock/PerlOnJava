#!/usr/bin/env perl
use strict;
use warnings;
use feature 'state';

use Scalar::Util qw(weaken);
use Test::More tests => 3;

sub persistent_metadata {
    state $metadata = { marker => 'kept by installed CODE state' };
    return $metadata;
}

my $weak_metadata = persistent_metadata();
weaken($weak_metadata);

ok(defined $weak_metadata,
    'installed CODE state keeps its unblessed metadata alive');
is($weak_metadata->{marker}, 'kept by installed CODE state',
    'metadata remains usable after the return cleanup boundary');

undef *persistent_metadata;
ok(!defined $weak_metadata,
    'removing the installed CODE root lets the weak metadata clear');
