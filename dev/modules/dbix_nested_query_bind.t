use strict;
use warnings;

BEGIN {
  my $lib = $ENV{DBIX_CLASS_LIB}
    or die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n";
  unshift @INC, $lib;
}

use Test::More;

{
  package DBIxClassNestedBind::Result::Tag;
  use base 'DBIx::Class::Core';
  __PACKAGE__->table('tags');
  __PACKAGE__->add_columns(
    tagid => { data_type => 'integer' },
    tag => { data_type => 'varchar', size => 100 },
  );
  __PACKAGE__->set_primary_key('tagid');

  package DBIxClassNestedBind::Schema;
  use base 'DBIx::Class::Schema';
  __PACKAGE__->register_class(Tag => 'DBIxClassNestedBind::Result::Tag');
}

my $schema = DBIxClassNestedBind::Schema->connect('dbi:SQLite:dbname=:memory:');
my $dbh = $schema->storage->dbh;
$dbh->do('CREATE TABLE tags (tagid INTEGER PRIMARY KEY, tag VARCHAR(100))');
$dbh->do('INSERT INTO tags (tagid, tag) VALUES (?, ?)', undef, 1, 'Blue');
$dbh->do('INSERT INTO tags (tagid, tag) VALUES (?, ?)', undef, 2, 'Blue');
$dbh->do('INSERT INTO tags (tagid, tag) VALUES (?, ?)', undef, 3, 'Cheesy');
$dbh->do('INSERT INTO tags (tagid, tag) VALUES (?, ?)', undef, 4, 'Cheesy');
$dbh->do('INSERT INTO tags (tagid, tag) VALUES (?, ?)', undef, 5, 'Green');

my $rs = $schema->resultset('Tag')->search(
  { tag => [ 'Blue', 'Cheesy' ] },
  { group_by => 'tag' },
);

my $nested_query = $rs->as_query;
my @bind_indices = (1 .. $#$$nested_query);
is_deeply(\@bind_indices, [ 1, 2 ],
  'range over the returned packed query reaches all bind records');
my @transported_binds;
push @transported_binds, @{$$nested_query}[1 .. $#$$nested_query];
is(scalar(@transported_binds), 2,
  'nested array slice transports both bind records');
is_deeply([ map { $_->[1] } @transported_binds ], [ 'Blue', 'Cheesy' ],
  'nested array slice transports the original bind values');
my $query = $rs->count_rs->as_query;
is(ref($query), 'REF', 'count query is a packed subquery reference');
is(ref($$query), 'ARRAY', 'packed query contains SQL and bind records');
is(scalar(@{$$query}), 3, 'both bind records survive nested FROM generation');
is_deeply(
  [ $$query->[1][1], $$query->[2][1] ],
  [ 'Blue', 'Cheesy' ],
  'count query preserves the original bind values',
);
is($rs->count, 2, 'grouped count executes with its nested bind values');

done_testing;
