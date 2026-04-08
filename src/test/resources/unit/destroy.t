use Test::More;

subtest 'DESTROY called at scope exit' => sub {
    my @log;
    { package DestroyBasic;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    { my $obj = DestroyBasic->new; }
    is_deeply(\@log, ["destroyed"], "DESTROY called at scope exit");
};

subtest 'DESTROY with multiple references' => sub {
    my @log;
    { package DestroyMulti;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my $a = DestroyMulti->new;
    my $b = $a;
    undef $a;
    is_deeply(\@log, [], "DESTROY not called with refs remaining");
    undef $b;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last ref gone");
};

subtest 'DESTROY exception becomes warning' => sub {
    my $warned = 0;
    local $SIG{__WARN__} = sub { $warned++ if $_[0] =~ /in cleanup/ };
    { package DestroyException;
      sub new { bless {}, shift }
      sub DESTROY { die "oops" } }
    { my $obj = DestroyException->new; }
    ok($warned, "DESTROY exception became a warning");
};

subtest 'DESTROY on undef' => sub {
    my @log;
    { package DestroyUndef;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my $obj = DestroyUndef->new;
    undef $obj;
    is_deeply(\@log, ["destroyed"], "DESTROY called on undef");
};

subtest 'DESTROY on hash delete' => sub {
    my @log;
    { package DestroyDelete;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my %h;
    $h{obj} = DestroyDelete->new;
    delete $h{obj};
    is_deeply(\@log, ["destroyed"], "DESTROY called on hash delete");
};

subtest 'DESTROY not called twice' => sub {
    my $count = 0;
    { package DestroyOnce;
      sub new { bless {}, shift }
      sub DESTROY { $count++ } }
    { my $obj = DestroyOnce->new;
      undef $obj; }
    is($count, 1, "DESTROY called exactly once");
};

subtest 'DESTROY inheritance' => sub {
    my @log;
    { package DestroyParent;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "parent" } }
    { package DestroyChild;
      our @ISA = ('DestroyParent');
      sub new { bless {}, shift } }
    { my $obj = DestroyChild->new; }
    is_deeply(\@log, ["parent"], "DESTROY inherited from parent");
};

subtest 'Return value not destroyed' => sub {
    my @log;
    { package DestroyReturn;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    sub make_obj { my $obj = DestroyReturn->new; return $obj }
    my $x = make_obj();
    is_deeply(\@log, [], "returned object not destroyed");
    undef $x;
    is_deeply(\@log, ["destroyed"], "destroyed when last ref gone");
};

subtest 'No DESTROY on blessed without DESTROY method' => sub {
    my $destroyed = 0;
    { package NoDESTROY;
      sub new { bless {}, shift } }
    { my $obj = NoDESTROY->new; }
    is($destroyed, 0, "no DESTROY called when class has none");
};

subtest 'Re-bless to class without DESTROY' => sub {
    my @log;
    { package HasDestroy;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    { package NoDestroy2;
      sub new { bless {}, shift } }
    my $obj = HasDestroy->new;
    bless $obj, 'NoDestroy2';
    undef $obj;
    is_deeply(\@log, [], "DESTROY not called after re-bless to class without DESTROY");
};

subtest 'DESTROY on hash delete returns value' => sub {
    my @log;
    { package DestroyDeleteReturn;
      sub new { bless { data => 42 }, shift }
      sub DESTROY { push @log, "destroyed" } }
    my %h;
    $h{obj} = DestroyDeleteReturn->new;
    my $val = delete $h{obj};
    is_deeply(\@log, [], "DESTROY not called while return value alive");
    is($val->{data}, 42, "deleted value still accessible");
    undef $val;
    is_deeply(\@log, ["destroyed"], "DESTROY called after return value dropped");
};

subtest 'DESTROY on hash delete in void context' => sub {
    my @log;
    { package DestroyDeleteVoid;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my %h;
    $h{obj} = DestroyDeleteVoid->new;
    delete $h{obj};  # void context — no one captures the return value
    is_deeply(\@log, ["destroyed"],
        "DESTROY called at statement end for void-context delete (mortal mechanism)");
};

done_testing();
