use strict;
use warnings;
use utf8;
use Test::More tests => 7;
use DBI;
use DBD::SQLite;

{
    package DBIRegression::BigramTokenizer;

    sub get_tokenizer {
        return sub {
            my ($text) = @_;
            my $offset = 0;
            return sub {
                return if $offset + 2 > length($text);
                my $start = $offset++;
                return (substr($text, $start, 2), 2, $start, $start + 2, $start);
            };
        };
    }
}

{
    package DBIRegression::UnigramTokenizer;

    sub get_tokenizer {
        return sub {
            my ($text) = @_;
            my $offset = 0;
            return sub {
                return if $offset >= length($text);
                my $start = $offset++;
                return (substr($text, $start, 1), 1, $start, $start + 1, $start);
            };
        };
    }
}

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
    sqlite_unicode => 1,
});
ok($dbh, 'connected to SQLite');

$dbh->do(q{
    CREATE VIRTUAL TABLE fts_ngrams USING fts4(
        content,
        tokenize=perl 'DBIRegression::BigramTokenizer::get_tokenizer'
    )
});
pass('created FTS4 table with a Perl tokenizer');

$dbh->do('INSERT INTO fts_ngrams (content) VALUES (?)', undef, 'ハンプティ');
pass('inserted UTF-8 text through the Perl tokenizer');

my ($match) = $dbh->selectrow_array(
    'SELECT content FROM fts_ngrams WHERE content MATCH ?',
    undef,
    'ンプ',
);
is($match, 'ハンプティ', 'FTS4 query uses tokens emitted by the Perl callback');

$dbh->do(q{
    CREATE VIRTUAL TABLE fts_unigrams USING fts4(
        content,
        tokenize=perl 'DBIRegression::UnigramTokenizer::get_tokenizer'
    )
});
pass('created FTS4 table with a Perl unigram tokenizer');

$dbh->do('INSERT INTO fts_unigrams (content) VALUES (?)', undef, 'ハンプティ・ダンプティ 塀の上');
pass('inserted text for phrase and boolean query');

my ($phrase_match) = $dbh->selectrow_array(
    'SELECT content FROM fts_unigrams WHERE content MATCH ?',
    undef,
    '"ハンプティ" AND "ダンプティ"',
);
is($phrase_match, 'ハンプティ・ダンプティ 塀の上',
    'FTS4 applies Perl unigram tokens to quoted phrases joined by AND');
