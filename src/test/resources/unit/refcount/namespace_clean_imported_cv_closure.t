use strict;
use warnings;
use Test::More tests => 1;

package Issue2043::ImportedHelper;
use Exporter 'import';
our @EXPORT_OK;
BEGIN { @EXPORT_OK = qw(gettimeofday) }
sub gettimeofday { 42 }

package Issue2043::ClosureCaller;
BEGIN { Issue2043::ImportedHelper->import('gettimeofday') }
our $callback = sub { gettimeofday() };
# Model namespace::clean removing the imported stash entry after this callsite
# has been compiled but before the closure is invoked.
CHECK { delete $Issue2043::ClosureCaller::{gettimeofday} }

package main;
is($Issue2043::ClosureCaller::callback->(), 42,
    'compiled closure keeps imported CV callable after namespace cleanup');
