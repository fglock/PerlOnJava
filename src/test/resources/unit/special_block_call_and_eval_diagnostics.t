use v5.18;
use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use IPC::Open3 qw(open3);
use Symbol qw(gensym);
use Test::More;

my $stderr = gensym;
my @command = $^O eq 'MSWin32'
    ? ($^X, '-e', q!use re 'eval'; my $code = '(?{BEGIN{die})'; eval { 'a' =~ /^a$code/ }; print $@!)
    : ('timeout', '60', $^X, '-e', q!use re 'eval'; my $code = '(?{BEGIN{die})'; eval { 'a' =~ /^a$code/ }; print $@!);
my $pid = open3(undef, my $stdout, $stderr, @command);
my $diagnostic = do { local $/; <$stdout> // '' }
    . do { local $/; <$stderr> // '' };
waitpid($pid, 0);
like($diagnostic, qr/BEGIN failed--compilation aborted at \(eval \d+\) line \d+/,
    'BEGIN failure in regex eval retains the Perl diagnostic boundary');

state sub END { shift }
is(eval { END('lexical END call') }, 'lexical END call',
    'a lexical END sub call is not parsed as a special-block declaration');

my @prototype_command = $^O eq 'MSWin32'
    ? ($^X, '-e', q!BEGIN() {10} foreach my $p (sort {lc($a) cmp lc($b)} keys %v)!)
    : ('timeout', '60', $^X, '-e', q!BEGIN() {10} foreach my $p (sort {lc($a) cmp lc($b)} keys %v)!);
my $prototype_stderr = gensym;
my $prototype_pid = open3(undef, my $prototype_stdout, $prototype_stderr, @prototype_command);
my $prototype_diagnostic = do { local $/; <$prototype_stdout> // '' }
    . do { local $/; <$prototype_stderr> // '' };
waitpid($prototype_pid, 0);
like($prototype_diagnostic,
    qr/\APrototype on BEGIN block ignored at -e line 1\.\nsyntax error at -e line 1, at EOF\nExecution of -e aborted due to compilation errors\./,
    'a special-block prototype warning is retained through a terminal parse error');

require Config;
sub stash_cv_shape { 42 }
is(ref($main::{stash_cv_shape}), 'CODE',
    'a code-only stash entry retains its CODE shape');

done_testing;
