use strict;
use warnings;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);
use Test::More;

my $is_jperl = defined &Internals::jperl_gc;
plan skip_all => 'nested PerlOnJava launcher required' unless $is_jperl;

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $source = join ' ',
    'BEGIN { print "begin\\n"; }',
    'UNITCHECK { print "unitcheck\\n"; }',
    'CHECK { print "check\\n"; }',
    'INIT { print "init\\n"; }',
    'END { print "end\\n"; }',
    'print "main\\n";',
    'BEGIN { exit 7; }';

my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, 'timeout', '60', $launcher, '-e', $source);
my $out = do { local $/; <$stdout> // '' };
my $err = do { local $/; <$stderr> // '' };
waitpid($pid, 0);

is($out . $err, "begin\nunitcheck\ncheck\nend\n",
   'compile-time exit drains UNITCHECK and CHECK before END');
is($? >> 8, 7, 'compile-time exit preserves its status');

done_testing;
