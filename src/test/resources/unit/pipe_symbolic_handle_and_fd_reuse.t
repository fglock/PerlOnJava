#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 5;
no strict 'refs';

my $symbolic = 'pipe_symbolic_regression';
my $writer = 'pipe_symbolic_writer_regression';
ok(pipe($symbolic, $writer), 'pipe accepts scalar values naming symbolic handles');
ok(close $symbolic, 'pipe reader is installed in the named glob');
ok(close $writer, 'pipe writer is installed in the named glob');
ok(!close $symbolic, 'closing the scalar name again sees the closed named glob');

# Keep this last: it closes the process stdin so the next open must reuse fd 0.
close STDIN or die "close STDIN: $!";
open my $fh, '<', __FILE__ or die "open test source: $!";
is(fileno($fh), 0, 'open reuses fd 0 after STDIN is closed');
close $fh or die "close file: $!";
