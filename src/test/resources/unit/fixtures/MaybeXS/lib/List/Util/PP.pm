package List::Util::PP;
use strict;
use warnings;
use Exporter ();

our $VERSION = '1.500015';
$VERSION =~ tr/_//d;

our @EXPORT_OK;
BEGIN {
  @EXPORT_OK = qw(
    all any first none notall
    min max minstr maxstr
    product reductions reduce sum sum0
    sample shuffle
    uniq uniqnum uniqint uniqstr
    pairs unpairs pairkeys pairvalues pairmap pairgrep pairfirst
    head tail
    zip zip_longest zip_shortest
    mesh mesh_longest mesh_shortest
  );
}

my $rand = do { our $RAND };
*RAND = *List::Util::RAND;
our $RAND;
$RAND = $rand
  if !defined $RAND;

sub import {
  my $pkg = caller;

  # (RT88848) Touch the caller's $a and $b, to avoid the warning of
  #   Name "main::a" used only once: possible typo" warning
  no strict 'refs';
  ${"${pkg}::a"} = ${"${pkg}::a"};
  ${"${pkg}::b"} = ${"${pkg}::b"};

  # May be imported by List::Util if very old version is installed, which
  # expects default exports
  if ($pkg eq 'List::Util' && @_ < 2) {
    package #hide from PAUSE
      List::Util;
    return __PACKAGE__->import(qw(first min max minstr maxstr reduce sum shuffle));
  }

  goto &Exporter::import;
}

sub reduce (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  return shift unless @_ > 1;

  my $pkg = caller;
  my $a = shift;

  no strict 'refs';
  local *{"${pkg}::a"} = \$a;
  my $glob_b = \*{"${pkg}::b"};

  foreach my $b (@_) {
    local *$glob_b = \$b;
    $a = $f->();
  }

  $a;
}

sub reductions (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  return unless @_;
  return shift unless @_ > 1;

  my $pkg = caller;
  my $a = shift;

  no strict 'refs';
  local *{"${pkg}::a"} = \$a;
  my $glob_b = \*{"${pkg}::b"};

  my @o = $a;

  foreach my $b (@_) {
    local *$glob_b = \$b;
    $a = $f->();
    push @o, $a;
  }

  @o;
}

sub first (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  $f->() and return $_
    foreach @_;

  undef;
}

sub sum (@) {
  return undef unless @_;
  my $s = 0;
  $s += $_ foreach @_;
  return $s;
}

sub min (@) {
  return undef unless @_;
  my $min = shift;
  $_ < $min and $min = $_
    foreach @_;
  return $min;
}

sub max (@) {
  return undef unless @_;
  my $max = shift;
  $_ > $max and $max = $_
    foreach @_;
  return $max;
}

sub minstr (@) {
  return undef unless @_;
  my $min = shift;
  $_ lt $min and $min = $_
    foreach @_;
  return $min;
}

sub maxstr (@) {
  return undef unless @_;
  my $max = shift;
  $_ gt $max and $max = $_
    foreach @_;
  return $max;
}

sub shuffle (@) {
  sample(scalar @_, @_);
}

sub sample ($@) {
  my $num = shift;
  my @i = (0 .. $#_);
  $num = @_ if $num > @_;
  my @o = defined $RAND ? (map +(splice @i, $RAND->(scalar @i), 1), 1 .. $num)
                        : (map +(splice @i,    rand(scalar @i), 1), 1 .. $num);
  @_[@o];
}

sub all (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  $f->() or return !!0
    foreach @_;
  return !!1;
}

sub any (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  $f->() and return !!1
    foreach @_;
  return !!0;
}

sub none (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  $f->() and return !!0
    foreach @_;
  return !!1;
}

sub notall (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  $f->() or return !!1
    foreach @_;
  return !!0;
}

sub product (@) {
  my $p = 1;
  $p *= $_ foreach @_;
  return $p;
}

sub sum0 (@) {
  my $s = 0;
  $s += $_ foreach @_;
  return $s;
}

sub pairs (@) {
  if (@_ % 2) {
    warnings::warnif('misc', 'Odd number of elements in pairs');
  }

  return
    map { bless [ @_[$_, $_ + 1] ], 'List::Util::PP::_Pair' }
    map $_*2,
    0 .. int($#_/2);
}

sub unpairs (@) {
  map @{$_}[0,1], @_;
}

sub pairkeys (@) {
  if (@_ % 2) {
    warnings::warnif('misc', 'Odd number of elements in pairkeys');
  }

  return
    map $_[$_*2],
    0 .. int($#_/2);
}

sub pairvalues (@) {
  if (@_ % 2) {
    require Carp;
    warnings::warnif('misc', 'Odd number of elements in pairvalues');
  }

  return
    map $_[$_*2 + 1],
    0 .. int($#_/2);
}

sub pairmap (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  if (@_ % 2) {
    warnings::warnif('misc', 'Odd number of elements in pairmap');
  }

  my $pkg = caller;
  no strict 'refs';
  my $glob_a = \*{"${pkg}::a"};
  my $glob_b = \*{"${pkg}::b"};

  return
    map {
      local (*$glob_a, *$glob_b) = \( @_[$_,$_+1] );
      $f->();
    }
    map $_*2,
    0 .. int($#_/2);
}

sub pairgrep (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  if (@_ % 2) {
    warnings::warnif('misc', 'Odd number of elements in pairgrep');
  }

  my $pkg = caller;
  no strict 'refs';
  my $glob_a = \*{"${pkg}::a"};
  my $glob_b = \*{"${pkg}::b"};

  return
    map {
      local (*$glob_a, *$glob_b) = \( @_[$_,$_+1] );
      $f->() ? (wantarray ? @_[$_,$_+1] : 1) : ();
    }
    map $_*2,
    0 .. int ($#_/2);
}

sub pairfirst (&@) {
  my $f = shift;
  unless ( length ref $f && eval { $f = \&$f; 1 } ) {
    require Carp;
    Carp::croak("Not a subroutine reference");
  }

  if (@_ % 2) {
    warnings::warnif('misc', 'Odd number of elements in pairfirst');
  }

  my $pkg = caller;
  no strict 'refs';
  my $glob_a = \*{"${pkg}::a"};
  my $glob_b = \*{"${pkg}::b"};

  foreach my $i (map $_*2, 0 .. int($#_/2)) {
    local (*$glob_a, *$glob_b) = \( @_[$i,$i+1] );
    return wantarray ? @_[$i,$i+1] : 1
      if $f->();
  }
  return ();
}

sub List::Util::PP::_Pair::key   { $_[0][0] }
sub List::Util::PP::_Pair::value { $_[0][1] }
sub List::Util::PP::_Pair::TO_JSON { [ @{$_[0]} ] }

sub uniq (@) {
  my %seen;
  my $undef;
  my @uniq = grep defined($_) ? !$seen{$_}++ : !$undef++, @_;
  @uniq;
}

sub uniqnum (@) {
  my %seen;
  my $sv;
  require B;
  my $b = B::svref_2object(\$sv);
  my @uniq =
    grep {
      my $nv = $_;
      my $k;
      if (ref $nv && defined &overload::ov_method && defined &overload::mycan) {
        my $package = ref $nv;
        # also covers Math::BigInt and Math::BigFloat
        if (UNIVERSAL::isa($nv, 'Math::BigInt')) {
          $k = $nv->bstr;
        }
        elsif(my $method
          = overload::ov_method(overload::mycan($package, '(0+'), $package)
          || overload::ov_method(overload::mycan($package, '""'), $package)
          || overload::ov_method(overload::mycan($package, 'bool'), $package)
        ) {
          $nv = $nv->$method(undef, !!0);
        }
        elsif (
          my $nomethod = overload::ov_method(overload::mycan($package, '(nomethod'), $package)
        ) {
          $nv = $nv->$nomethod(undef, undef, '0+');
        }
      }

      if (defined $k) {
      }
      elsif (ref $nv) {
        $k = 'R' . 0+$nv;
      }
      elsif ($nv == 0) {
        $k = '0';
      }
      elsif ($nv*0 != $nv*0) {
        $k = sprintf '%f', $nv;
      }
      elsif (int($nv) != $nv) {
        $k = 'N' . pack('F', $nv);
      }
      else {
        $sv = $nv + 0;
        my $flags = $b->FLAGS;
        if ($flags & B::SVf_IVisUV()) {
          $k = sprintf '%u', $nv;
        }
        elsif ($flags & B::SVf_IOK()) {
          $k = sprintf '%d', $nv;
        }
        elsif ($flags & B::SVf_NOK()) {
          $k = sprintf '%.0f', $nv;
        }
        else {
          $k = $nv;
        }
      }
      !$seen{$k}++;
    }
    map +(defined($_) ? $_
      : do { warnings::warnif('uninitialized', 'Use of uninitialized value in subroutine entry'); 0 }),
    @_;
  @uniq;
}

sub uniqint (@) {
  my %seen;
  my @uniq =
    map +(
      ref $_ ? $_ : int($_)
    ),
    grep {
      !$seen{
        /\A[0-9]+\z/  ? $_
        : $_ > 0      ? sprintf '%u', $_
                      : sprintf '%d', $_
      }++;
    }
    map +(defined($_) ? $_
      : do { warnings::warnif('uninitialized', 'Use of uninitialized value in subroutine entry'); 0 }),
    @_;
  @uniq;
}

sub uniqstr (@) {
  my %seen;
  my @uniq =
    grep !$seen{$_}++,
    map +(defined($_) ? $_
      : do { warnings::warnif('uninitialized', 'Use of uninitialized value in subroutine entry'); '' }),
    @_;
  @uniq;
}

sub head ($@) {
  my $size = shift;
  return @_
    if $size > @_;
  @_[ 0 .. ( $size >= 0 ? $size - 1 : $#_ + $size ) ];
}

sub tail ($@) {
  my $size = shift;
  return @_
    if $size > @_;
  @_[ ( $size >= 0 ? ($#_ - ($size-1) ) : 0 - $size ) .. $#_ ];
}

sub zip_longest {
  return unless @_;
  map {
    my $idx = $_;
    [ map $_->[$idx], @_ ];
  } ( 0 .. max(map $#$_, @_) );
}

sub zip_shortest {
  return unless @_;
  map {
    my $idx = $_;
    [ map $_->[$idx], @_ ];
  } ( 0 .. min(map $#$_, @_) );
}

*zip = \&zip_longest;

sub mesh_longest {
  return unless @_;
  map {
    my $idx = $_;
    map $_->[$idx], @_;
  } ( 0 .. max(map $#$_, @_) );
}

sub mesh_shortest {
  return unless @_;
  map {
    my $idx = $_;
    map $_->[$idx], @_;
  } ( 0 .. min(map $#$_, @_) );
}

*mesh = \&mesh_longest;

1;

__END__

=head1 NAME

List::Util::PP - Pure-perl implementations of List::Util subroutines

=head1 SYNOPSIS

  use List::Util::PP qw(
    reduce any all none notall first reductions

    max maxstr min minstr product sum sum0

    pairs unpairs pairkeys pairvalues pairfirst pairgrep pairmap

    shuffle uniq uniqint uniqnum uniqstr zip mesh
  );

=head1 DESCRIPTION

C<List::Util::PP> contains pure-perl implementations of all of the functions
documented in L<List::Util>.  This is meant for when a compiler is not
available, or when packaging for reuse without without installing modules.

Generally, L<List::Util::MaybeXS> should be used instead, which will
automatically use the faster XS implementation when possible, but fall back on
this module otherwise.

=head1 FUNCTIONS

=over

=item L<all|List::Util/all>

=item L<any|List::Util/any>

=item L<first|List::Util/first>

=item L<min|List::Util/min>

=item L<max|List::Util/max>

=item L<minstr|List::Util/minstr>

=item L<maxstr|List::Util/maxstr>

=item L<none|List::Util/none>

=item L<notall|List::Util/notall>

=item L<product|List::Util/product>

=item L<reduce|List::Util/reduce>

=item L<reductions|List::Util/reductions>

=item L<sum|List::Util/sum>

=item L<sum0|List::Util/sum0>

=item L<shuffle|List::Util/shuffle>

=item L<sample|List::Util/sample>

=item L<uniq|List::Util/uniq>

=item L<uniqnum|List::Util/uniqnum>

=item L<uniqint|List::Util/uniqint>

=item L<uniqstr|List::Util/uniqstr>

=item L<pairs|List::Util/pairs>

=item L<unpairs|List::Util/unpairs>

=item L<pairkeys|List::Util/pairkeys>

=item L<pairvalues|List::Util/pairvalues>

=item L<pairmap|List::Util/pairmap>

=item L<pairgrep|List::Util/pairgrep>

=item L<pairfirst|List::Util/pairfirst>

=item L<head|List::Util/head>

=item L<tail|List::Util/tail>

=item L<zip|List::Util/zip>

=item L<zip_longest|List::Util/zip>

=item L<zip_shortest|List::Util/zip>

=item L<mesh|List::Util/mesh>

=item L<mesh_longest|List::Util/mesh>

=item L<mesh_shortest|List::Util/mesh>

=back

=head1 CONFIGURATION VARIABLES

=over 4

=item L<$RAND|List::Util/$RAND>

The variables C<$List::Util::RAND>, C<$List::Util::PP::RAND>, and
C<$List::Util::MaybeXS::RAND> are all aliased to each other.  Any of them will
impact both List::Util::PP and L<List::Util> functions.

=back

=head1 SUPPORT

See L<List::Util::MaybeXS> for support and contact information.

=head1 AUTHORS

See L<List::Util::MaybeXS> for authors.

=head1 COPYRIGHT AND LICENSE

See L<List::Util::MaybeXS> for the copyright and license.

=cut
