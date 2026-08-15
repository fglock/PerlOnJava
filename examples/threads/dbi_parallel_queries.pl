#!/usr/bin/env perl
use strict;
use warnings;

use DBI;
use File::Temp qw(tempfile);
use threads;

my ($temporary, $database) = tempfile(SUFFIX => '.sqlite');
close $temporary;
my $dsn = "dbi:SQLite:dbname=$database";

my $setup = DBI->connect($dsn, '', '', {
    RaiseError => 1,
    PrintError => 0,
});
$setup->do('create table measurements (bucket integer, value integer)');
my $insert = $setup->prepare('insert into measurements values (?, ?)');
$insert->execute($_ % 3, $_) for 1 .. 12;
$setup->disconnect;

sub query_bucket {
    my ($child_dsn, $bucket) = @_;
    # DBI handles are native runtime resources. Create and destroy them in the
    # owning ithread; only ordinary Perl data crosses join().
    my $dbh = DBI->connect($child_dsn, '', '', {
        RaiseError => 1,
        PrintError => 0,
    });
    my ($count, $sum) = $dbh->selectrow_array(
        'select count(*), sum(value) from measurements where bucket = ?',
        undef,
        $bucket,
    );
    $dbh->disconnect;
    return { bucket => $bucket, count => $count, sum => $sum };
}

my @workers = map { threads->create(\&query_bucket, $dsn, $_) } 0 .. 2;
my @results = sort { $a->{bucket} <=> $b->{bucket} }
    map { $_->join } @workers;

die "unexpected row counts\n"
    unless join(',', map { $_->{count} } @results) eq '4,4,4';
die "unexpected aggregate\n"
    unless join(',', map { $_->{sum} } @results) eq '30,22,26';
die "temporary database could not be removed\n"
    unless unlink($database) || !-e $database;

print "three child-owned connections returned sums 30,22,26\n";
