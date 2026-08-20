use strict;
use utf8;
use threads;
use Test::More;

ok("abc" =~ /\A(*script_run:.*)\z/,
   'a single Latin script run matches');
ok("אבג" =~ /\A(*sr:.*)\z/,
   'short script-run spelling matches Hebrew');
ok("a\N{HEBREW LETTER ALEF}" !~ /\A(*script_run:.*)\z/,
   'mixed scripts do not match');
ok("あ漢" =~ /\A(*script_run:.*)\z/,
   'Japanese Han and Hiragana share a permitted run');
ok("\x{0378}" =~ /\A(*script_run:.)\z/,
   'one Unknown code point is permitted');
ok("\x{0378}\x{0379}" !~ /\A(*script_run:..)\z/,
   'two Unknown code points are rejected');
ok("1\x{0E51}" !~ /\A(*script_run:..)\z/,
   'different decimal digit sets are rejected');

ok("abc" =~ /\A(*script_run:(?:a|ab))c\z/,
   'ordinary script_run can backtrack within its body');
ok("abc" !~ /\A(*atomic_script_run:(?:a|ab))c\z/,
   'atomic script_run does not backtrack within its body');
ok("abc" !~ /\A(*asr:(?:a|ab))c\z/,
   'atomic short spelling has the same boundary');

my $thread_pattern = threads->create(sub { qr/\A(*sr:.*)\z/ })->join;
ok("אבג" =~ $thread_pattern,
   'script-run regex retains semantics across thread snapshot join');
ok("a\N{HEBREW LETTER ALEF}" !~ $thread_pattern,
   'thread snapshot retains mixed-script rejection');

done_testing;
