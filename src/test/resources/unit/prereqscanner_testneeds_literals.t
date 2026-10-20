use strict;
use warnings;
use Test::More;

my $loaded = eval {
    require Perl::PrereqScanner;
    require PPIx::Literal;
    1;
};
plan skip_all => 'Perl::PrereqScanner and PPIx::Literal are not installed'
    unless $loaded;
plan tests => 3;

my $scanner = Perl::PrereqScanner->new({ scanners => ['TestNeeds'] });
is_deeply(
    $scanner->scan_string(q{use Test::Needs 'Local::Module1'})->as_string_hash,
    { 'Local::Module1' => 0 },
    'quoted module name is preserved',
);
is_deeply(
    $scanner->scan_string(q{use Test::Needs 'Local::Module1', 'Local::Module2'})->as_string_hash,
    { 'Local::Module1' => 0, 'Local::Module2' => 0 },
    'multiple quoted module names are preserved',
);
is_deeply(
    $scanner->scan_string(q{use Test::Needs { perl => 5.020 }})->as_string_hash,
    { perl => '5.020' },
    'version literal retains its significant digits',
);
