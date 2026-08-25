#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempfile);

sub write_source {
    my ($source) = @_;
    my ($fh, $path) = tempfile();
    print {$fh} $source;
    close $fh or die "close $path: $!";
    return $path;
}

my $object_file = write_source(<<'PERL');
use v5.38;
bless { marker => 'application' }, 'ModuleTrue::Application';
PERL

my $application = do $object_file;
is($@, '', 'do-file with module_true compiles and runs');
isa_ok($application, 'ModuleTrue::Application', 'do-file preserves a true object result');
is($application->{marker}, 'application', 'preserved object retains its contents');

my $false_file = write_source(<<'PERL');
use v5.38;
0;
PERL

my $false = do $false_file;
is($false, 0, 'module_true does not replace a false do-file result');

