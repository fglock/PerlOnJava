use v5.18;
use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use IPC::Open3 qw(open3);
use Symbol qw(gensym);
use Test::More;

my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, 'timeout', '60', $^X, '-e',
    q!use re 'eval'; my $code = '(?{BEGIN{die})'; eval { 'a' =~ /^a$code/ }; print $@!);
my $diagnostic = do { local $/; <$stdout> // '' }
    . do { local $/; <$stderr> // '' };
waitpid($pid, 0);
like($diagnostic, qr/BEGIN failed--compilation aborted at \(eval \d+\) line \d+/,
    'BEGIN failure in regex eval retains the Perl diagnostic boundary');

state sub END { shift }
is(eval { END('lexical END call') }, 'lexical END call',
    'a lexical END sub call is not parsed as a special-block declaration');

done_testing;
