#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $dir = tempdir(CLEANUP => 1);
make_path("$dir/NestedFailure");

open my $base, '>', "$dir/NestedFailure/Base.pm" or die $!;
print {$base} <<'PM';
package NestedFailure::Base;
sub defined_before_failure { 'partial' }
require NestedFailure::MissingDependency;
sub defined_after_failure { 'unreachable' }
1;
PM
close $base;

open my $child, '>', "$dir/NestedFailure/Child.pm" or die $!;
print {$child} <<'PM';
package NestedFailure::Child;
use base 'NestedFailure::Base';
sub child_method { 'child' }
1;
PM
close $child;

{
    local @INC = ($dir, @INC);
    my $loaded = eval { require NestedFailure::Child; 1 };
    ok(!$loaded, 'use base rejects a parent with a missing nested dependency');
    like(
        $@,
        qr/Can't locate NestedFailure\/MissingDependency\.pm/,
        'nested require error propagates from the base module',
    );
    ok(
        !defined $INC{'NestedFailure/Base.pm'},
        'partially compiled base is not marked as successfully loaded',
    );
    ok(
        !defined $INC{'NestedFailure/Child.pm'},
        'child with failed base import is not marked as successfully loaded',
    );
    ok(
        !NestedFailure::Child->can('child_method'),
        'child compilation stops after its base fails to load',
    );
}

done_testing();
