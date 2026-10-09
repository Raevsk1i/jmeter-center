package recovery

import "testing"

func TestAdoptFencingOverridesHigherLocal(t *testing.T) {
	s := NewState()
	if prev := s.AdoptFencing(10); prev != 0 {
		t.Fatalf("prev=%d", prev)
	}
	if prev := s.AdoptFencing(3); prev != 10 {
		t.Fatalf("prev=%d want 10", prev)
	}
	if s.CurrentFencing() != 3 {
		t.Fatalf("current=%d want 3", s.CurrentFencing())
	}
}

func TestAcceptFencingRejectsStale(t *testing.T) {
	s := NewState()
	s.AdoptFencing(5)
	if s.AcceptFencing(4) {
		t.Fatal("expected reject")
	}
	if !s.AcceptFencing(5) {
		t.Fatal("expected accept equal")
	}
	if !s.AcceptFencing(6) {
		t.Fatal("expected accept higher")
	}
	if s.CurrentFencing() != 6 {
		t.Fatalf("current=%d", s.CurrentFencing())
	}
}
