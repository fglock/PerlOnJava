use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

# Keep q/qq handler failures deferred so that both diagnostics are emitted.
# This mirrors the multi-form diagnostic contract exercised by perl5_t's
# lib/croak/toke test without relying on its exact source offsets.
my $source = <<'PERL';
use overload;
BEGIN { overload::constant q => sub {} }
q(1);
qq(1$_);
PERL

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

isnt($? >> 8, 0, 'undefined q handler fails compilation');
like($output, qr/Constant\(q\): .*near /,
    'q handler diagnostic retains q-specific near context');
like($output, qr/Constant\(qq\): .*within string/,
    'qq handler diagnostic is retained after q fails');

done_testing;
