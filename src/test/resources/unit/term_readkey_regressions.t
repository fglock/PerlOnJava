use Test::More tests => 4;
use Term::ReadKey ();

ok(defined &Term::ReadKey::termoptions, 'termoptions is available');
ok(defined &Term::ReadKey::blockoptions, 'blockoptions is available');
ok(defined &Term::ReadKey::termsizeoptions, 'termsizeoptions is available');

my %chars;
my $ok = eval 'sub _term_readkey_set_control_chars_compiles { Term::ReadKey::SetControlChars(%chars, *STDIN) } 1';
is($@, '', 'SetControlChars accepts hash-style key/value list syntax');
