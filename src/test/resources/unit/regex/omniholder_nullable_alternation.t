use strict;
use warnings;
use Test::More;
require './src/main/perl/lib/DBIx/Simple.pm';

my $quoted = qr/(?:'[^']*'|"[^"]*")*/;
my $sql = 'SELECT * FROM t WHERE id IN (??)';

my @binds = (10, 20, 30);
my $rewritten = $sql;
my $replacements = 0;
$rewritten =~ s[($quoted|\(\?\?\))] {
    $1 eq '(??)'
        ? do { ++$replacements; '(' . join(', ', ('?') x @binds) . ')' }
        : $1
}eg;
is($rewritten, 'SELECT * FROM t WHERE id IN (?, ?, ?)',
    'exact DBIx substitution expands the omniholder');
is($replacements, 1, 'exact DBIx substitution expands it once');

my $quoted_sql = q{SELECT '(??)', (??)};
$replacements = 0;
$quoted_sql =~ s[($quoted|\(\?\?\))] {
    $1 eq '(??)' ? do { ++$replacements; '(?)' } : $1
}eg;
is($quoted_sql, q{SELECT '(??)', (?)},
    'exact substitution leaves quoted omniholder untouched');
is($replacements, 1, 'only the unquoted omniholder is replaced');

my $db = bless { dbd => 'SQLite' }, 'DBIx::Simple';

my $dbix_sql = 'INSERT INTO t VALUES (??)';
$db->_replace_omniholder(\$dbix_sql, [10, 20, 30]);
is($dbix_sql, 'INSERT INTO t VALUES (?, ?, ?)',
    'bundled DBIx::Simple expands an unquoted omniholder');

my $dbix_quoted_sql = q{INSERT INTO t VALUES ('(??)', (??))};
$db->_replace_omniholder(\$dbix_quoted_sql, [10, 20]);
is($dbix_quoted_sql, q{INSERT INTO t VALUES ('(??)', (?, ?))},
    'bundled DBIx::Simple protects quoted text and expands the later omniholder');

done_testing;
