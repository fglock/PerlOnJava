use strict;
use warnings;
use File::Temp qw(tempfile tempdir);
use Test::More tests => 9;

sub check {
    my ($name, $code) = @_;
    my $result = eval $code;
    my $error = $@;
    diag "$name: $error" if $error ne '';
    ok(defined($result) && $result, $name);
}

check('smartmatch_value', q{
    use feature 'switch';
    my $x = 2; my $matched = 0;
    given ($x) { when (2) { $matched = 1 } }
    $matched;
});

check('restricted_hash_enforced', q{
    require Hash::Util;
    my %h = (a => 1);
    Hash::Util::lock_keys(\%h, 'a');
    eval { $h{b} = 2; 1 } ? 0 : 1;
});

check('overload_increment_value', q{
    { package Audit::Inc;
      use overload '++' => sub { $_[0]->{value}++; $_[0] }, '0+' => sub { $_[0]->{value} };
      use overload '+' => sub { bless { value => $_[0]->{value} + ($_[1] // 0) }, ref($_[0]) },
        '0+' => sub { $_[0]->{value} }, '""' => sub { "$_[0]->{value}" };
      sub new { bless { value => $_[1] }, $_[0] }
    }
    my $x = Audit::Inc->new(1); ++$x; "$x" eq '2';
});

check('caller_extended_count', q{
    sub audit_caller_count { my @c = caller(0); scalar(@c) }
    audit_caller_count() >= 9;
});

check('dbm_round_trip', q{
    my $dir = tempdir(CLEANUP => 1);
    my $base = "$dir/audit-db";
    my %db;
    dbmopen(%db, $base, 0666) or die "dbmopen: $!";
    $db{key} = 'value';
    dbmclose(%db) or die "dbmclose: $!";
    dbmopen(%db, $base, 0666) or die "dbmopen 2: $!";
    my $ok = $db{key} eq 'value';
    dbmclose(%db);
    $ok;
});

check('fork_defined', q{
    my $pid = fork();
    if (defined $pid && $pid == 0) { exit 0 }
    defined $pid;
});

check('dump_statement', q{
    eval 'dump';
    1;
});

check('multibyte_seek_tell', q{
    my ($fh, $path) = tempfile(UNLINK => 1);
    binmode $fh, ':encoding(UTF-8)';
    print {$fh} "éx";
    seek($fh, 0, 0) or die "seek: $!";
    my $pos = tell($fh);
    defined $pos;
});

check('ops_module_load', q{
    require ops;
    1;
});

diag "perl_version=$^V";
