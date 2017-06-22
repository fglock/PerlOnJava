#!./perl

{
 package Basic;
 use Tie::Array;
 @ISA = qw(Tie::Array);

 sub TIEARRAY  { return bless [], shift }
 sub FETCH     { $_[0]->[$_[1]] }
 sub STORE     { $_[0]->[$_[1]] = $_[2] }
 sub FETCHSIZE { scalar(@{$_[0]}) }
 sub STORESIZE { $#{$_[0]} = $_[1]-1 }
}

tie @x,Basic;
tie @get,Basic;
tie @got,Basic;
tie @tests,Basic;

@tests = split(/\n/, <<EOF);
0 3,			0 1 2,		3 4 5 6 7
0 0 a b c,		,		a b c 0 1 2 3 4 5 6 7
8 0 a b c,		,		0 1 2 3 4 5 6 7 a b c
7 0 6.5,		,		0 1 2 3 4 5 6 6.5 7
1 0 a b c d e f g h i j,,		0 a b c d e f g h i j 1 2 3 4 5 6 7
0 1 a,			0,		a 1 2 3 4 5 6 7
1 6 x y z,		1 2 3 4 5 6,	0 x y z 7
0 7 x y z,		0 1 2 3 4 5 6,	x y z 7
1 7 x y z,		1 2 3 4 5 6 7,	0 x y z
4,			4 5 6 7,	0 1 2 3
-4,			4 5 6 7,	0 1 2 3
EOF

print "1..", 2 + @tests, "\n";
die "blech" unless @tests;

@x = (1,2,3);
push(@x,@x);
if (join(':',@x) eq '1:2:3:1:2:3') {print "ok 1\n";} else {print "not ok 1\n";}
push(@x,4);
if (join(':',@x) eq '1:2:3:1:2:3:4') {print "ok 2\n";} else {print "not ok 2\n";}

### # test for push/pop intuiting @ on array
### {
###     no warnings 'deprecated';
###     push(x,3);
### }
### if (join(':',@x) eq '1:2:3:1:2:3:4:3') {print "ok 3\n";} else {print "not ok 3\n";}
### {
###     no warnings 'deprecated';
###     pop(x);
### }
### if (join(':',@x) eq '1:2:3:1:2:3:4') {print "ok 4\n";} else {print "not ok 4\n";}

$test = 3;
foreach $line (@tests) {
    ($list,$get,$leave) = split(/,\t*/,$line);
    ($pos, $len, @list) = split(' ',$list);
    @get = split(' ',$get);
    @leave = split(' ',$leave);
    @x = (0,1,2,3,4,5,6,7);
    if (defined $len) {

    print "# list [$list] \n";
    print "# splice [@x] [$pos] [$len] [@list] \n";

	@got = splice(@x, $pos, $len, @list);
    }
    else {
	@got = splice(@x, $pos);
    }
    if (join(':',@got) eq join(':',@get) &&
	join(':',@x) eq join(':',@leave)) {
	print "ok ",$test++,"\n";
    }
    else {
	print "not ok ",$test++," got: @got == @get left: @x == @leave\n";
    }
}

1;  # this file is require'd by lib/tie-stdpush.t
